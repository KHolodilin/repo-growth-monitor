package com.kholodilin.repogrowth.topic.worker;

import com.kholodilin.repogrowth.collection.worker.RepositoryLock;
import com.kholodilin.repogrowth.collection.worker.RetryPolicy;
import com.kholodilin.repogrowth.common.config.CollectionProperties;
import com.kholodilin.repogrowth.common.logging.LogMdc;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import com.kholodilin.repogrowth.github.model.GitHubSearchItem;
import com.kholodilin.repogrowth.github.model.GitHubSearchResponse;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
import com.kholodilin.repogrowth.topic.domain.TopicResult;
import com.kholodilin.repogrowth.topic.domain.TopicRun;
import com.kholodilin.repogrowth.topic.domain.TopicWatch;
import com.kholodilin.repogrowth.topic.persistence.TopicResultJdbcRepository;
import com.kholodilin.repogrowth.topic.persistence.TopicRunJdbcRepository;
import com.kholodilin.repogrowth.topic.persistence.TopicWatchJdbcRepository;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class TopicWorker {

    private final TopicRunJdbcRepository runRepository;
    private final TopicWatchJdbcRepository watchRepository;
    private final TopicResultJdbcRepository resultRepository;
    private final RepositoryJdbcRepository repositoryJdbcRepository;
    private final GitHubClient gitHubClient;
    private final RepositoryLock repositoryLock;
    private final RetryPolicy retryPolicy;
    private final CollectionProperties collectionProperties;
    private final TransactionTemplate transactionTemplate;

    public TopicWorker(
            TopicRunJdbcRepository runRepository,
            TopicWatchJdbcRepository watchRepository,
            TopicResultJdbcRepository resultRepository,
            RepositoryJdbcRepository repositoryJdbcRepository,
            GitHubClient gitHubClient,
            RepositoryLock repositoryLock,
            RetryPolicy retryPolicy,
            CollectionProperties collectionProperties,
            TransactionTemplate transactionTemplate
    ) {
        this.runRepository = runRepository;
        this.watchRepository = watchRepository;
        this.resultRepository = resultRepository;
        this.repositoryJdbcRepository = repositoryJdbcRepository;
        this.gitHubClient = gitHubClient;
        this.repositoryLock = repositoryLock;
        this.retryPolicy = retryPolicy;
        this.collectionProperties = collectionProperties;
        this.transactionTemplate = transactionTemplate;
    }

    public boolean poll(String workerId) {
        Optional<TopicRun> claimed = transactionTemplate.execute(status ->
                runRepository.claim(workerId, orDefault(collectionProperties.jobLease(), Duration.ofMinutes(5)))
        );
        if (claimed == null || claimed.isEmpty()) {
            return false;
        }
        process(claimed.get());
        return true;
    }

    private void process(TopicRun run) {
        LogMdc.repositoryId(run.repositoryId());
        Timer.Sample sample = Timer.start(Metrics.globalRegistry);
        try (Connection lockConnection = repositoryLock.openConnection()) {
            boolean locked = repositoryLock.tryLock(lockConnection, run.repositoryId());
            if (!locked) {
                SearchRunStatus rollback = run.attempt() <= 1 ? SearchRunStatus.READY : SearchRunStatus.RETRY;
                runRepository.releaseClaim(run.id(), rollback);
                return;
            }
            try {
                TopicWatch watch = watchRepository.findById(run.topicWatchId()).orElseThrow();
                Repository repository = repositoryJdbcRepository.findById(run.repositoryId()).orElseThrow();
                GitHubSearchResponse response = gitHubClient.searchRepositories(
                        watch.searchQuery(),
                        watch.resultLimit(),
                        watch.sort(),
                        watch.sortOrder()
                );
                List<TopicResult> results = new ArrayList<>();
                Integer position = null;
                int index = 1;
                for (GitHubSearchItem item : response.itemsOrEmpty()) {
                    if (index > watch.resultLimit()) {
                        break;
                    }
                    results.add(fromItem(run.id(), index, item));
                    if (item.id() == repository.githubId()) {
                        position = index;
                    }
                    index++;
                }
                Integer trackedPosition = position;
                transactionTemplate.executeWithoutResult(status -> {
                    resultRepository.replaceAll(run.id(), results);
                    runRepository.markSuccess(run.id(), response.totalCount(), trackedPosition);
                });
                log.info("Topic run succeeded watchId={} position={}", watch.id(), trackedPosition);
            } finally {
                repositoryLock.unlock(lockConnection, run.repositoryId());
            }
        } catch (GitHubException ex) {
            handleFailure(run, ex.retryable(), ex.errorCode().name(), ex.getMessage(), ex.rateLimitReset());
        } catch (Exception ex) {
            handleFailure(run, true, "INTERNAL_ERROR", ex.getMessage(), null);
        } finally {
            sample.stop(Timer.builder("topic.duration").register(Metrics.globalRegistry));
            LogMdc.clearJob();
        }
    }

    private static TopicResult fromItem(long topicRunId, int position, GitHubSearchItem item) {
        String owner = item.owner() == null ? "" : item.owner().login();
        return new TopicResult(
                null,
                topicRunId,
                position,
                item.id(),
                item.fullName(),
                owner,
                item.stargazersCount(),
                0,
                item.forksCount(),
                item.language(),
                item.description(),
                item.htmlUrl()
        );
    }

    private void handleFailure(
            TopicRun run,
            boolean retryable,
            String errorCode,
            String message,
            java.time.Instant rateLimitReset
    ) {
        log.warn("Topic run failed retryable={} errorCode={} attempt={}", retryable, errorCode, run.attempt());
        transactionTemplate.executeWithoutResult(status -> {
            if (retryable && !retryPolicy.exhausted(run.attempt())) {
                runRepository.markRetry(
                        run.id(),
                        retryPolicy.nextAttemptAt(run.attempt(), rateLimitReset),
                        errorCode,
                        message
                );
            } else {
                runRepository.markFailed(run.id(), errorCode, message);
            }
        });
    }

    private Duration orDefault(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }
}
