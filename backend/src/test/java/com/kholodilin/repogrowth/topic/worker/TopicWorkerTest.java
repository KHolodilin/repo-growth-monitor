package com.kholodilin.repogrowth.topic.worker;

import com.kholodilin.repogrowth.collection.worker.RepositoryLock;
import com.kholodilin.repogrowth.collection.worker.RetryPolicy;
import com.kholodilin.repogrowth.common.config.CollectionProperties;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import com.kholodilin.repogrowth.github.model.GitHubSearchItem;
import com.kholodilin.repogrowth.github.model.GitHubSearchResponse;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
import com.kholodilin.repogrowth.topic.domain.TopicRun;
import com.kholodilin.repogrowth.topic.domain.TopicWatch;
import com.kholodilin.repogrowth.topic.persistence.TopicResultJdbcRepository;
import com.kholodilin.repogrowth.topic.persistence.TopicRunJdbcRepository;
import com.kholodilin.repogrowth.topic.persistence.TopicWatchJdbcRepository;
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
class TopicWorkerTest {

    @Mock
    TopicRunJdbcRepository runRepository;
    @Mock
    TopicWatchJdbcRepository watchRepository;
    @Mock
    TopicResultJdbcRepository resultRepository;
    @Mock
    RepositoryJdbcRepository repositoryJdbcRepository;
    @Mock
    GitHubClient gitHubClient;
    @Mock
    RepositoryLock repositoryLock;
    @Mock
    TransactionTemplate transactionTemplate;
    @Mock
    Connection connection;

    private TopicWorker worker;
    private TopicRun run;

    @BeforeEach
    void setUp() {
        worker = worker(Duration.ofMinutes(5));
        Instant now = Instant.parse("2026-09-23T06:00:00Z");
        run = new TopicRun(
                9L, 5L, 13L, LocalDate.of(2026, 9, 23), SearchRunStatus.RUNNING, 1,
                null, "w1", now, now, null, null, null, null, null, null
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
    void pollReturnsFalseWhenTheClaimTransactionYieldsNull() {
        doReturn(null).when(transactionTemplate).execute(any());
        assertThat(worker.poll("w1")).isFalse();
    }

    @Test
    void pollReleasesABusyLock() throws Exception {
        when(runRepository.claim(eq("w1"), any())).thenReturn(Optional.of(run));
        when(repositoryLock.openConnection()).thenReturn(connection);
        when(repositoryLock.tryLock(connection, 13L)).thenReturn(false);
        assertThat(worker.poll("w1")).isTrue();
        verify(runRepository).releaseClaim(9L, SearchRunStatus.READY);
    }

    @Test
    void pollStoresTheTrackedPositionAndStopsAtTheLimit() throws Exception {
        TopicWatch watch = new TopicWatch(5L, 13L, "kafka", null, "stars", "desc", true, 1, Instant.EPOCH, Instant.EPOCH);
        Repository repository = new Repository(
                13L, 301L, 1L, "demo", "acme/demo", "demo", "PUBLIC", "main", "Java",
                false, false, 1, 0, 0, 0, 0, true, Instant.EPOCH, Instant.EPOCH, null, null, null, null, null, null
        );
        GitHubSearchItem tracked = item(301L, "acme/demo");
        GitHubSearchItem extra = item(9L, "other/lib");
        when(runRepository.claim(eq("w1"), any())).thenReturn(Optional.of(run));
        when(repositoryLock.openConnection()).thenReturn(connection);
        when(repositoryLock.tryLock(connection, 13L)).thenReturn(true);
        when(watchRepository.findById(5L)).thenReturn(Optional.of(watch));
        when(repositoryJdbcRepository.findById(13L)).thenReturn(Optional.of(repository));
        when(gitHubClient.searchRepositories("topic:kafka", 1, "stars", "desc"))
                .thenReturn(new GitHubSearchResponse(12, List.of(tracked, extra)));

        assertThat(worker.poll("w1")).isTrue();
        verify(runRepository).markSuccess(9L, 12, 1);
        verify(repositoryLock).unlock(connection, 13L);
    }

    @Test
    void pollRetriesAndThenFails() throws Exception {
        TopicWatch watch = new TopicWatch(5L, 13L, "kafka", "Java", "stars", "desc", true, 50, Instant.EPOCH, Instant.EPOCH);
        Repository repository = new Repository(
                13L, 301L, 1L, "demo", "acme/demo", "demo", "PUBLIC", "main", "Java",
                false, false, 1, 0, 0, 0, 0, true, Instant.EPOCH, Instant.EPOCH, null, null, null, null, null, null
        );
        when(runRepository.claim(eq("w1"), any())).thenReturn(Optional.of(run));
        when(repositoryLock.openConnection()).thenReturn(connection);
        when(repositoryLock.tryLock(connection, 13L)).thenReturn(true);
        when(watchRepository.findById(5L)).thenReturn(Optional.of(watch));
        when(repositoryJdbcRepository.findById(13L)).thenReturn(Optional.of(repository));
        when(gitHubClient.searchRepositories("topic:kafka language:Java", 50, "stars", "desc"))
                .thenThrow(GitHubException.rateLimit(null, "slow"));

        assertThat(worker.poll("w1")).isTrue();
        verify(runRepository).markRetry(eq(9L), any(), eq("GITHUB_RATE_LIMIT_EXCEEDED"), eq("slow"));

        TopicRun exhausted = new TopicRun(
                9L, 5L, 13L, LocalDate.of(2026, 9, 23), SearchRunStatus.RUNNING, 8,
                null, "w1", Instant.EPOCH, Instant.EPOCH, null, null, null, null, null, null
        );
        when(runRepository.claim(eq("w1"), any())).thenReturn(Optional.of(exhausted));
        when(repositoryLock.openConnection()).thenThrow(new IllegalStateException("db"));
        assertThat(worker.poll("w1")).isTrue();
        verify(runRepository).markFailed(9L, "INTERNAL_ERROR", "db");
    }

    @Test
    void pollUsesTheDefaultLeaseWhenNoneIsConfigured() {
        worker = worker(null);
        when(runRepository.claim(eq("w1"), eq(Duration.ofMinutes(5)))).thenReturn(Optional.empty());
        assertThat(worker.poll("w1")).isFalse();
    }

    private TopicWorker worker(Duration lease) {
        return new TopicWorker(
                runRepository,
                watchRepository,
                resultRepository,
                repositoryJdbcRepository,
                gitHubClient,
                repositoryLock,
                new RetryPolicy(),
                new CollectionProperties(1, lease, new CollectionProperties.Planner(true, LocalTime.of(10, 0), LocalTime.of(18, 0), Duration.ofMinutes(5), Duration.ofHours(3))),
                transactionTemplate
        );
    }

    private static GitHubSearchItem item(long id, String fullName) {
        return new GitHubSearchItem(id, fullName, null, 1, 0, "Java", "demo", "https://github.com/" + fullName, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
    }
}
