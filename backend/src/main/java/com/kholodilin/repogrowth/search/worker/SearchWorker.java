package com.kholodilin.repogrowth.search.worker;

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
import com.kholodilin.repogrowth.search.domain.SearchQuery;
import com.kholodilin.repogrowth.search.domain.SearchResult;
import com.kholodilin.repogrowth.search.domain.SearchRun;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
import com.kholodilin.repogrowth.search.persistence.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchResultJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchRunJdbcRepository;
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
public class SearchWorker {

    private final SearchRunJdbcRepository runRepository;
    private final SearchQueryJdbcRepository queryRepository;
    private final SearchResultJdbcRepository resultRepository;
    private final RepositoryJdbcRepository repositoryJdbcRepository;
    private final GitHubClient gitHubClient;
    private final RepositoryLock repositoryLock;
    private final RetryPolicy retryPolicy;
    private final CollectionProperties collectionProperties;
    private final TransactionTemplate transactionTemplate;

    public SearchWorker(
            SearchRunJdbcRepository runRepository,
            SearchQueryJdbcRepository queryRepository,
            SearchResultJdbcRepository resultRepository,
            RepositoryJdbcRepository repositoryJdbcRepository,
            GitHubClient gitHubClient,
            RepositoryLock repositoryLock,
            RetryPolicy retryPolicy,
            CollectionProperties collectionProperties,
            TransactionTemplate transactionTemplate
    ) {
        this.runRepository = runRepository;
        this.queryRepository = queryRepository;
        this.resultRepository = resultRepository;
        this.repositoryJdbcRepository = repositoryJdbcRepository;
        this.gitHubClient = gitHubClient;
        this.repositoryLock = repositoryLock;
        this.retryPolicy = retryPolicy;
        this.collectionProperties = collectionProperties;
        this.transactionTemplate = transactionTemplate;
    }

    public boolean poll(String workerId) {
        Optional<SearchRun> claimed = transactionTemplate.execute(status ->
                runRepository.claim(workerId, orDefault(collectionProperties.jobLease(), Duration.ofMinutes(5)))
        );
        if (claimed == null || claimed.isEmpty()) {
            return false;
        }
        process(claimed.get());
        return true;
    }

    private void process(SearchRun run) {
        LogMdc.repositoryId(run.repositoryId());
        LogMdc.searchRunId(run.id());
        Timer.Sample sample = Timer.start(Metrics.globalRegistry);
        try (Connection lockConnection = repositoryLock.openConnection()) {
            boolean locked = repositoryLock.tryLock(lockConnection, run.repositoryId());
            if (!locked) {
                SearchRunStatus rollback = run.attempt() <= 1 ? SearchRunStatus.READY : SearchRunStatus.RETRY;
                runRepository.releaseClaim(run.id(), rollback);
                return;
            }
            try {
                SearchQuery query = queryRepository.findById(run.searchQueryId()).orElseThrow();
                Repository repository = repositoryJdbcRepository.findById(run.repositoryId()).orElseThrow();
                GitHubSearchResponse response = gitHubClient.searchRepositories(query.query(), query.resultLimit());
                List<SearchResult> results = new ArrayList<>();
                Integer position = null;
                int index = 1;
                for (GitHubSearchItem item : response.itemsOrEmpty()) {
                    if (index > query.resultLimit()) {
                        break;
                    }
                    String ownerLogin = item.owner() == null ? "" : item.owner().login();
                    results.add(new SearchResult(
                            null,
                            run.id(),
                            index,
                            item.id(),
                            item.fullName(),
                            ownerLogin,
                            item.stargazersCount(),
                            item.forksCount(),
                            item.language(),
                            item.description(),
                            item.createdAt(),
                            item.updatedAt()
                    ));
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
                log.info("Search run succeeded queryId={} position={}", query.id(), trackedPosition);
            } finally {
                repositoryLock.unlock(lockConnection, run.repositoryId());
            }
        } catch (GitHubException ex) {
            handleFailure(run, ex.retryable(), ex.errorCode().name(), ex.getMessage(), ex.rateLimitReset());
        } catch (Exception ex) {
            handleFailure(run, true, "INTERNAL_ERROR", ex.getMessage(), null);
        } finally {
            sample.stop(Timer.builder("search.duration").register(Metrics.globalRegistry));
            LogMdc.clearJob();
        }
    }

    private void handleFailure(
            SearchRun run,
            boolean retryable,
            String errorCode,
            String message,
            java.time.Instant rateLimitReset
    ) {
        log.warn("Search run failed retryable={} errorCode={} attempt={}", retryable, errorCode, run.attempt());
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
