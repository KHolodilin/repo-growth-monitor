package com.kholodilin.repogrowth.search.worker;

import com.kholodilin.repogrowth.collection.worker.RepositoryLock;
import com.kholodilin.repogrowth.collection.worker.RetryPolicy;
import com.kholodilin.repogrowth.common.config.CollectionProperties;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import com.kholodilin.repogrowth.github.model.GitHubSearchItem;
import com.kholodilin.repogrowth.github.model.GitHubSearchResponse;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.search.application.RepositoryEnricher;
import com.kholodilin.repogrowth.search.domain.ActivityStatus;
import com.kholodilin.repogrowth.search.domain.SearchQuery;
import com.kholodilin.repogrowth.search.domain.SearchResult;
import com.kholodilin.repogrowth.search.domain.SearchRun;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
import com.kholodilin.repogrowth.search.persistence.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchResultJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchRunJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchWorkerTest {

    @Mock
    SearchRunJdbcRepository runRepository;
    @Mock
    SearchQueryJdbcRepository queryRepository;
    @Mock
    SearchResultJdbcRepository resultRepository;
    @Mock
    RepositoryJdbcRepository repositoryJdbcRepository;
    @Mock
    GitHubClient gitHubClient;
    @Mock
    RepositoryLock repositoryLock;
    @Mock
    TransactionTemplate transactionTemplate;
    @Mock
    RepositoryEnricher repositoryEnricher;
    @Mock
    Connection connection;

    private SearchWorker worker;
    private SearchRun run;
    private Repository repository;
    private SearchQuery query;

    @BeforeEach
    void setUp() {
        CollectionProperties properties = new CollectionProperties(
                1,
                Duration.ofMinutes(5),
                new CollectionProperties.Planner(LocalTime.of(10, 0), LocalTime.of(18, 0), Duration.ofMinutes(5))
        );
        worker = new SearchWorker(
                runRepository,
                queryRepository,
                resultRepository,
                repositoryJdbcRepository,
                gitHubClient,
                repositoryLock,
                new RetryPolicy(),
                properties,
                transactionTemplate,
                repositoryEnricher
        );
        Instant now = Instant.parse("2026-09-23T06:00:00Z");
        run = new SearchRun(
                9L, 5L, 13L, LocalDate.of(2026, 9, 23), SearchRunStatus.RUNNING, 1,
                null, "w1", now, now, null, null, null, null, null, null, null
        );
        query = new SearchQuery(5L, 13L, "outbox", "outbox", true, 50, now, now);
        repository = new Repository(
                13L, 99L, 1L, "outbox", "acme/outbox", "desc", "PUBLIC", "main", "Java",
                false, false, 21, 0, 16, 1, 7, true, now, now, now, now, null, null, now, now
        );
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void pollReturnsFalseWhenNothingIsClaimed() {
        when(runRepository.claim(eq("w1"), any())).thenReturn(Optional.empty());
        assertThat(worker.poll("w1")).isFalse();
    }

    @Test
    void pollReleasesWhenRepositoryLockIsBusy() throws Exception {
        when(runRepository.claim(eq("w1"), any())).thenReturn(Optional.of(run));
        when(repositoryLock.openConnection()).thenReturn(connection);
        when(repositoryLock.tryLock(connection, 13L)).thenReturn(false);

        assertThat(worker.poll("w1")).isTrue();
        verify(runRepository).releaseClaim(9L, SearchRunStatus.READY);
    }

    @Test
    void pollStoresResultsAndTracksTheRepositoryPosition() throws Exception {
        GitHubSearchItem item = new GitHubSearchItem(
                99L, "acme/outbox", null, 21, 16, "Java", "desc", "https://github.com/acme/outbox",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-09-23T00:00:00Z"), Instant.parse("2026-09-11T00:00:00Z")
        );
        SearchResult stored = new SearchResult(
                1L, 9L, 1, 99L, "acme/outbox", "acme", 21, 0, 16, 7, "Java", "desc",
                "https://github.com/acme/outbox", null, null, null, ActivityStatus.ACTIVE, null
        );
        when(runRepository.claim(eq("w1"), any())).thenReturn(Optional.of(run));
        when(repositoryLock.openConnection()).thenReturn(connection);
        when(repositoryLock.tryLock(connection, 13L)).thenReturn(true);
        when(queryRepository.findById(5L)).thenReturn(Optional.of(query));
        when(repositoryJdbcRepository.findById(13L)).thenReturn(Optional.of(repository));
        when(gitHubClient.searchRepositories("outbox", 50)).thenReturn(new GitHubSearchResponse(80, List.of(item)));
        when(repositoryEnricher.fromSearchItem(eq(9L), eq(1), eq(item))).thenReturn(stored);
        when(resultRepository.findByRun(9L)).thenReturn(List.of(stored));
        when(repositoryEnricher.enrich(stored, item)).thenReturn(stored);

        assertThat(worker.poll("w1")).isTrue();
        verify(runRepository).markSuccess(9L, 80, 1);
        verify(runRepository).markEnrichment(9L, "SUCCESS");
        verify(repositoryLock).unlock(connection, 13L);
    }

    @Test
    void pollRetriesRetryableGitHubErrors() throws Exception {
        when(runRepository.claim(eq("w1"), any())).thenReturn(Optional.of(run));
        when(repositoryLock.openConnection()).thenReturn(connection);
        when(repositoryLock.tryLock(connection, 13L)).thenReturn(true);
        when(queryRepository.findById(5L)).thenReturn(Optional.of(query));
        when(repositoryJdbcRepository.findById(13L)).thenReturn(Optional.of(repository));
        when(gitHubClient.searchRepositories("outbox", 50)).thenThrow(GitHubException.rateLimit(null, "slow"));

        assertThat(worker.poll("w1")).isTrue();
        verify(runRepository).markRetry(eq(9L), any(), eq("GITHUB_RATE_LIMIT_EXCEEDED"), eq("slow"));
    }

    @Test
    void pollMarksInternalErrorsAsFailedAfterRetryPolicy() throws Exception {
        SearchRun lastAttempt = new SearchRun(
                9L, 5L, 13L, LocalDate.of(2026, 9, 23), SearchRunStatus.RUNNING, 8,
                null, "w1", Instant.parse("2026-09-23T06:00:00Z"), Instant.parse("2026-09-23T06:00:00Z"),
                null, null, null, null, null, null, null
        );
        when(runRepository.claim(eq("w1"), any())).thenReturn(Optional.of(lastAttempt));
        when(repositoryLock.openConnection()).thenThrow(new IllegalStateException("db"));

        assertThat(worker.poll("w1")).isTrue();
        verify(runRepository).markFailed(9L, "INTERNAL_ERROR", "db");
    }

    @Test
    void pollReturnsFalseWhenClaimTransactionYieldsNull() {
        doReturn(null).when(transactionTemplate).execute(any());
        assertThat(worker.poll("w1")).isFalse();
    }

    @Test
    void pollReleasesBusyLockToRetryAfterTheFirstAttempt() throws Exception {
        SearchRun retryRun = new SearchRun(
                9L, 5L, 13L, LocalDate.of(2026, 9, 23), SearchRunStatus.RUNNING, 2,
                null, "w1", Instant.parse("2026-09-23T06:00:00Z"), Instant.parse("2026-09-23T06:00:00Z"),
                null, null, null, null, null, null, null
        );
        when(runRepository.claim(eq("w1"), any())).thenReturn(Optional.of(retryRun));
        when(repositoryLock.openConnection()).thenReturn(connection);
        when(repositoryLock.tryLock(connection, 13L)).thenReturn(false);

        assertThat(worker.poll("w1")).isTrue();
        verify(runRepository).releaseClaim(9L, SearchRunStatus.RETRY);
    }

    @Test
    void pollStopsAtTheQueryLimitAndMarksPartialEnrichment() throws Exception {
        SearchQuery limited = new SearchQuery(
                5L, 13L, "outbox", "outbox", true, 1, Instant.parse("2026-09-23T06:00:00Z"), Instant.parse("2026-09-23T06:00:00Z")
        );
        GitHubSearchItem first = new GitHubSearchItem(
                99L, "acme/outbox", null, 21, 16, "Java", "desc", "https://github.com/acme/outbox",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-09-23T00:00:00Z"), Instant.parse("2026-09-11T00:00:00Z")
        );
        GitHubSearchItem extra = new GitHubSearchItem(
                100L, "acme/other", null, 3, 1, "Java", "desc", "https://github.com/acme/other",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-09-23T00:00:00Z"), Instant.parse("2026-09-11T00:00:00Z")
        );
        SearchResult stored = new SearchResult(
                1L, 9L, 1, 99L, "acme/outbox", "acme", 21, 0, 16, 7, "Java", "desc",
                "https://github.com/acme/outbox", null, null, null, ActivityStatus.ACTIVE, null
        );
        SearchResult leftover = new SearchResult(
                2L, 9L, 2, 100L, "acme/other", "acme", 3, 0, 1, 1, "Java", "desc",
                "https://github.com/acme/other", null, null, null, ActivityStatus.ACTIVE, null
        );
        when(runRepository.claim(eq("w1"), any())).thenReturn(Optional.of(run));
        when(repositoryLock.openConnection()).thenReturn(connection);
        when(repositoryLock.tryLock(connection, 13L)).thenReturn(true);
        when(queryRepository.findById(5L)).thenReturn(Optional.of(limited));
        when(repositoryJdbcRepository.findById(13L)).thenReturn(Optional.of(repository));
        when(gitHubClient.searchRepositories("outbox", 1)).thenReturn(new GitHubSearchResponse(80, List.of(first, extra)));
        when(repositoryEnricher.fromSearchItem(eq(9L), eq(1), eq(first))).thenReturn(stored);
        when(resultRepository.findByRun(9L)).thenReturn(List.of(stored, leftover));
        when(repositoryEnricher.enrich(stored, first)).thenThrow(new IllegalStateException("enrich"));

        assertThat(worker.poll("w1")).isTrue();
        verify(runRepository).markSuccess(9L, 80, 1);
        verify(runRepository).markEnrichment(9L, "PARTIAL");
    }

    @Test
    void pollFailsNonRetryableGitHubErrorsImmediately() throws Exception {
        when(runRepository.claim(eq("w1"), any())).thenReturn(Optional.of(run));
        when(repositoryLock.openConnection()).thenReturn(connection);
        when(repositoryLock.tryLock(connection, 13L)).thenReturn(true);
        when(queryRepository.findById(5L)).thenReturn(Optional.of(query));
        when(repositoryJdbcRepository.findById(13L)).thenReturn(Optional.of(repository));
        when(gitHubClient.searchRepositories("outbox", 50)).thenThrow(GitHubException.auth(401, "no"));

        assertThat(worker.poll("w1")).isTrue();
        verify(runRepository).markFailed(9L, "GITHUB_AUTH_ERROR", "no");
    }

    @Test
    void pollUsesTheDefaultLeaseWhenTheConfiguredLeaseIsMissing() {
        worker = new SearchWorker(
                runRepository,
                queryRepository,
                resultRepository,
                repositoryJdbcRepository,
                gitHubClient,
                repositoryLock,
                new RetryPolicy(),
                new CollectionProperties(1, null, new CollectionProperties.Planner(LocalTime.of(10, 0), LocalTime.of(18, 0), Duration.ofMinutes(5))),
                transactionTemplate,
                repositoryEnricher
        );
        when(runRepository.claim(eq("w1"), any())).thenReturn(Optional.empty());
        assertThat(worker.poll("w1")).isFalse();
    }
}
