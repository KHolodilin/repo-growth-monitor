package com.kholodilin.repogrowth.collection.worker;

import com.kholodilin.repogrowth.collection.collector.Collector;
import com.kholodilin.repogrowth.common.api.ErrorCode;
import com.kholodilin.repogrowth.collection.collector.CollectorRegistry;
import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.common.config.CollectionProperties;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionWorkerTest {

    @Test
    void recordsAndClearsTheTrafficPermissionFlag() throws Exception {
        CollectionJobJdbcRepository jobs = mock(CollectionJobJdbcRepository.class);
        CollectionRunJdbcRepository runs = mock(CollectionRunJdbcRepository.class);
        RepositoryJdbcRepository repositories = mock(RepositoryJdbcRepository.class);
        GitHubOwnerJdbcRepository owners = mock(GitHubOwnerJdbcRepository.class);
        Collector collector = mock(Collector.class);
        RepositoryLock lock = mock(RepositoryLock.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        Connection connection = mock(Connection.class);
        Instant now = Instant.parse("2026-09-23T06:00:00Z");
        Repository repository = new Repository(
                13L, 99L, 1L, "outbox", "acme/outbox", "desc", "PUBLIC", "main", "Java",
                false, false, 21, 0, 16, 1, 7, true,
                now, now, now, now, null, null, now, now
        );
        when(collector.type()).thenReturn(CollectionJobType.TRAFFIC);
        when(transactions.execute(any())).thenReturn(Optional.of(trafficJob(now)));
        doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(lock.openConnection()).thenReturn(connection);
        when(lock.tryLock(connection, 13L)).thenReturn(true);
        when(repositories.findById(13L)).thenReturn(Optional.of(repository));
        when(owners.getById(1L)).thenReturn(new GitHubOwner(
                1L, 7L, "acme", OwnerType.USER, null, "https://github.com/acme", now, now
        ));
        doThrow(GitHubException.api(403, false, null, "forbidden")).when(collector).collect(any());
        CollectionWorker worker = new CollectionWorker(
                jobs,
                runs,
                repositories,
                owners,
                new CollectorRegistry(List.of(collector)),
                lock,
                new RetryPolicy(),
                new CollectionProperties(1, Duration.ofMinutes(5),
                        new CollectionProperties.Planner(LocalTime.of(3, 0), LocalTime.of(6, 0), Duration.ofHours(24))),
                transactions
        );

        assertThat(worker.poll("worker")).isTrue();
        verify(repositories).setTrafficPermissionMissing(13L, true);
        verify(jobs).markFailed(1L, "GITHUB_API_ERROR", "forbidden");

        doThrow(GitHubException.api(500, true, null, "down")).when(collector).collect(any());
        worker.poll("worker");
        verify(jobs).markRetry(eq(1L), any(), eq("GITHUB_API_ERROR"), eq("down"));

        doThrow(new IllegalStateException("boom")).when(collector).collect(any());
        worker.poll("worker");
        verify(jobs).markRetry(eq(1L), any(), eq("INTERNAL_ERROR"), eq("boom"));

        doThrow(new GitHubException(ErrorCode.VALIDATION_ERROR, 403, false, null, "nope")).when(collector).collect(any());
        worker.poll("worker");
        verify(jobs).markFailed(1L, "VALIDATION_ERROR", "nope");
        verify(repositories, org.mockito.Mockito.times(1)).setTrafficPermissionMissing(13L, true);

        org.mockito.Mockito.doNothing().when(collector).collect(any());
        worker.poll("worker");
        verify(repositories).setTrafficPermissionMissing(13L, false);
        verify(jobs).markSuccess(1L);

        Collector referrers = mock(Collector.class);
        when(referrers.type()).thenReturn(CollectionJobType.REFERRERS);
        CollectionWorker other = new CollectionWorker(
                jobs,
                runs,
                repositories,
                owners,
                new CollectorRegistry(List.of(referrers)),
                lock,
                new RetryPolicy(),
                new CollectionProperties(1, Duration.ofMinutes(5),
                        new CollectionProperties.Planner(LocalTime.of(3, 0), LocalTime.of(6, 0), Duration.ofHours(24))),
                transactions
        );
        when(transactions.execute(any())).thenReturn(Optional.of(new CollectionJob(
                2L, 1L, 13L, CollectionJobType.REFERRERS, LocalDate.parse("2026-09-23"),
                CollectionJobStatus.RUNNING, 1, now, "worker", now.plusSeconds(30), now, null,
                null, null
        )));
        other.poll("worker");
        verify(repositories, org.mockito.Mockito.times(1)).setTrafficPermissionMissing(13L, false);

        doThrow(new IllegalStateException("other")).when(referrers).collect(any());
        other.poll("worker");
        verify(jobs).markRetry(eq(2L), any(), eq("INTERNAL_ERROR"), eq("other"));
        verify(runs, org.mockito.Mockito.atLeastOnce()).refreshAggregates(1L);
    }

    private static CollectionJob trafficJob(Instant now) {
        return new CollectionJob(
                1L, 1L, 13L, CollectionJobType.TRAFFIC, LocalDate.parse("2026-09-23"),
                CollectionJobStatus.RUNNING, 1, now, "worker", now.plusSeconds(30), now, null,
                null, null
        );
    }
}
