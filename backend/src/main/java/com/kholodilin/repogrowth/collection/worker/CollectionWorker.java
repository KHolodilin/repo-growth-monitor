package com.kholodilin.repogrowth.collection.worker;

import com.kholodilin.repogrowth.collection.collector.CollectionContext;
import com.kholodilin.repogrowth.collection.collector.CollectorRegistry;
import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.common.config.CollectionProperties;
import com.kholodilin.repogrowth.common.logging.LogMdc;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.time.Duration;
import java.util.Optional;

@Slf4j
@Component
public class CollectionWorker {

    private final CollectionJobJdbcRepository jobRepository;
    private final CollectionRunJdbcRepository runRepository;
    private final RepositoryJdbcRepository repositoryJdbcRepository;
    private final GitHubOwnerJdbcRepository ownerJdbcRepository;
    private final CollectorRegistry collectorRegistry;
    private final RepositoryLock repositoryLock;
    private final RetryPolicy retryPolicy;
    private final CollectionProperties collectionProperties;
    private final TransactionTemplate transactionTemplate;

    public CollectionWorker(
            CollectionJobJdbcRepository jobRepository,
            CollectionRunJdbcRepository runRepository,
            RepositoryJdbcRepository repositoryJdbcRepository,
            GitHubOwnerJdbcRepository ownerJdbcRepository,
            CollectorRegistry collectorRegistry,
            RepositoryLock repositoryLock,
            RetryPolicy retryPolicy,
            CollectionProperties collectionProperties,
            TransactionTemplate transactionTemplate
    ) {
        this.jobRepository = jobRepository;
        this.runRepository = runRepository;
        this.repositoryJdbcRepository = repositoryJdbcRepository;
        this.ownerJdbcRepository = ownerJdbcRepository;
        this.collectorRegistry = collectorRegistry;
        this.repositoryLock = repositoryLock;
        this.retryPolicy = retryPolicy;
        this.collectionProperties = collectionProperties;
        this.transactionTemplate = transactionTemplate;
    }

    public boolean poll(String workerId) {
        Optional<CollectionJob> claimed = transactionTemplate.execute(status ->
                jobRepository.claim(workerId, orDefault(collectionProperties.jobLease(), Duration.ofMinutes(5)))
        );
        if (claimed == null || claimed.isEmpty()) {
            return false;
        }
        process(claimed.get());
        return true;
    }

    private void process(CollectionJob job) {
        LogMdc.repositoryId(job.repositoryId());
        LogMdc.collectionRunId(job.collectionRunId());
        LogMdc.collectionJobId(job.id());
        Timer.Sample sample = Timer.start(Metrics.globalRegistry);
        try (Connection lockConnection = repositoryLock.openConnection()) {
            boolean locked = repositoryLock.tryLock(lockConnection, job.repositoryId());
            if (!locked) {
                CollectionJobStatus rollback = job.attempt() <= 1 ? CollectionJobStatus.READY : CollectionJobStatus.RETRY;
                jobRepository.releaseClaim(job.id(), rollback);
                log.info("Repository lock busy, job released jobType={}", job.jobType());
                return;
            }
            try {
                Repository repository = repositoryJdbcRepository.findById(job.repositoryId()).orElseThrow();
                GitHubOwner owner = ownerJdbcRepository.getById(repository.ownerId());
                CollectionContext context = new CollectionContext(job, repository, owner.login());
                collectorRegistry.get(job.jobType()).collect(context);
                transactionTemplate.executeWithoutResult(status -> {
                    jobRepository.markSuccess(job.id());
                    runRepository.refreshAggregates(job.collectionRunId());
                });
                log.info("Collection job succeeded jobType={} attempt={}", job.jobType(), job.attempt());
            } finally {
                repositoryLock.unlock(lockConnection, job.repositoryId());
            }
        } catch (GitHubException ex) {
            handleFailure(job, ex.retryable(), ex.errorCode().name(), ex.getMessage(), ex.rateLimitReset());
        } catch (Exception ex) {
            handleFailure(job, true, "INTERNAL_ERROR", ex.getMessage(), null);
        } finally {
            sample.stop(Timer.builder("collection.duration").tag("jobType", job.jobType().name()).register(Metrics.globalRegistry));
            LogMdc.clearJob();
        }
    }

    private void handleFailure(
            CollectionJob job,
            boolean retryable,
            String errorCode,
            String message,
            java.time.Instant rateLimitReset
    ) {
        log.warn("Collection job failed jobType={} retryable={} errorCode={} attempt={}",
                job.jobType(), retryable, errorCode, job.attempt());
        transactionTemplate.executeWithoutResult(status -> {
            if (retryable && !retryPolicy.exhausted(job.attempt())) {
                jobRepository.markRetry(
                        job.id(),
                        retryPolicy.nextAttemptAt(job.attempt(), rateLimitReset),
                        errorCode,
                        message
                );
            } else {
                jobRepository.markFailed(job.id(), errorCode, message);
            }
            runRepository.refreshAggregates(job.collectionRunId());
        });
    }

    private Duration orDefault(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }
}
