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
import com.kholodilin.repogrowth.search.application.RepositoryEnricher;
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
    private final RepositoryEnricher repositoryEnricher;

    public SearchWorker(
            SearchRunJdbcRepository runRepository,
            SearchQueryJdbcRepository queryRepository,
            SearchResultJdbcRepository resultRepository,
            RepositoryJdbcRepository repositoryJdbcRepository,
            GitHubClient gitHubClient,
            RepositoryLock repositoryLock,
            RetryPolicy retryPolicy,
            CollectionProperties collectionProperties,
            TransactionTemplate transactionTemplate,
            RepositoryEnricher repositoryEnricher
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
        this.repositoryEnricher = repositoryEnricher;
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
                List<GitHubSearchItem> items = response.itemsOrEmpty();
                Integer position = trackedPosition(items, repository.githubId(), query.resultLimit());
                if (position == null && items.size() < expectedResultCount(query.resultLimit(), response.totalCount())) {
                    retryIncomplete(run);
                    return;
                }
                if (position == null && missingRankNeedsConfirmation(run, query)) {
                    confirmMissingRank(run);
                    return;
                }
                List<SearchResult> results = new ArrayList<>();
                int index = 1;
                for (GitHubSearchItem item : items) {
                    if (index > query.resultLimit()) {
                        break;
                    }
                    results.add(repositoryEnricher.fromSearchItem(run.id(), index, item));
                    index++;
                }
                Integer trackedPosition = position;
                transactionTemplate.executeWithoutResult(status -> {
                    resultRepository.replaceAll(run.id(), results);
                    runRepository.markSuccess(run.id(), response.totalCount(), trackedPosition);
                });
                enrichResults(run.id(), items);
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

    private void enrichResults(long searchRunId, List<GitHubSearchItem> items) {
        List<SearchResult> stored = resultRepository.findByRun(searchRunId);
        boolean partial = false;
        for (int i = 0; i < stored.size(); i++) {
            GitHubSearchItem item = i < items.size() ? items.get(i) : null;
            if (item == null) {
                continue;
            }
            try {
                SearchResult enriched = repositoryEnricher.enrich(stored.get(i), item);
                resultRepository.updateSnapshot(enriched);
            } catch (Exception ex) {
                partial = true;
                log.warn("Search enrichment failed fullName={} error={}", stored.get(i).fullName(), ex.getMessage());
            }
        }
        runRepository.markEnrichment(searchRunId, partial ? "PARTIAL" : "SUCCESS");
    }

    /**
     * A null position is a real miss only when GitHub returned a full page. Fewer rows than
     * {@code min(limit, totalCount)} is a truncated response and must not become "left the top".
     */
    private static int expectedResultCount(int resultLimit, int totalCount) {
        return Math.min(resultLimit, totalCount);
    }

    private static Integer trackedPosition(List<GitHubSearchItem> items, long githubId, int resultLimit) {
        int index = 1;
        for (GitHubSearchItem item : items) {
            if (index > resultLimit) {
                return null;
            }
            if (item.id() == githubId) {
                return index;
            }
            index++;
        }
        return null;
    }

    private void retryIncomplete(SearchRun run) {
        String message = "Search results were shorter than the requested depth";
        log.warn("Search run incomplete attempt={} queryId={}", run.attempt(), run.searchQueryId());
        transactionTemplate.executeWithoutResult(status -> {
            if (retryPolicy.exhausted(run.attempt())) {
                runRepository.markFailed(run.id(), "SEARCH_RESULTS_INCOMPLETE", message);
            } else {
                runRepository.markRetry(
                        run.id(),
                        retryPolicy.nextAttemptAt(run.attempt(), null),
                        "SEARCH_RESULTS_INCOMPLETE",
                        message
                );
            }
        });
    }

    /**
     * One confirmation before accepting that a repository ranked inside the depth has disappeared.
     * The confirmation attempt keeps {@code SEARCH_RANK_UNCONFIRMED} and may store a null position.
     */
    private boolean missingRankNeedsConfirmation(SearchRun run, SearchQuery query) {
        if ("SEARCH_RANK_UNCONFIRMED".equals(run.errorCode())) {
            return false;
        }
        return runRepository.previousSnapshot(run.searchQueryId(), run.businessDate())
                .map(SearchRun::trackedRepositoryPosition)
                .filter(position -> position <= query.resultLimit())
                .isPresent();
    }

    private void confirmMissingRank(SearchRun run) {
        log.warn("Search rank missing inside the previous depth queryId={}", run.searchQueryId());
        transactionTemplate.executeWithoutResult(status -> runRepository.markRetry(
                run.id(),
                retryPolicy.nextAttemptAt(run.attempt(), null),
                "SEARCH_RANK_UNCONFIRMED",
                "Tracked repository was inside the depth and is missing from a full result list"
        ));
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
