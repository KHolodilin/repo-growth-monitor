package com.kholodilin.repogrowth.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.collection.model.CollectionJob;
import com.kholodilin.repogrowth.collection.model.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.model.CollectionJobType;
import com.kholodilin.repogrowth.collection.model.CollectionRun;
import com.kholodilin.repogrowth.collection.model.CollectionRunStatus;
import com.kholodilin.repogrowth.collection.repository.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.repository.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.identity.security.CurrentUserAccess;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CollectionStatusServiceTest {

  @Mock RepositoryService repositories;
  @Mock CollectionRunJdbcRepository runs;
  @Mock CollectionJobJdbcRepository jobs;
  @Mock CurrentUserAccess currentUser;

  CollectionStatusService service;
  Repository publicRepo;
  Repository privateRepo;

  @BeforeEach
  void setUp() {
    service = new CollectionStatusService(repositories, runs, jobs, currentUser);
    Instant now = Instant.parse("2026-11-10T06:00:00Z");
    publicRepo =
        new Repository(
            13L,
            99L,
            1L,
            "outbox",
            "acme/outbox",
            "d",
            "PUBLIC",
            "main",
            "Java",
            false,
            false,
            21,
            0,
            16,
            1,
            7,
            true,
            now,
            now,
            now,
            now,
            null,
            null,
            now,
            now);
    privateRepo =
        new Repository(
            14L,
            100L,
            1L,
            "secret",
            "acme/secret",
            "d",
            "PRIVATE",
            "main",
            "Java",
            false,
            false,
            1,
            0,
            0,
            0,
            1,
            true,
            now,
            now,
            now,
            now,
            null,
            null,
            now,
            now);
  }

  @Test
  void adminSeesPrivateReposAndOwnerFilterNarrowsRows() {
    when(currentUser.canSeePrivateRepositories()).thenReturn(true);
    when(repositories.tracked()).thenReturn(List.of(publicRepo, privateRepo));
    Instant sync = Instant.parse("2026-11-10T05:00:00Z");
    LocalDate day = LocalDate.of(2026, 11, 10);
    CollectionRun run =
        new CollectionRun(9L, 13L, day, CollectionRunStatus.RUNNING, 1, 0, 0, sync, null);
    when(runs.latestPerTrackedRepository()).thenReturn(List.of(run));
    when(runs.latestCompletedAtForTracked()).thenReturn(sync);
    when(jobs.findByRunIds(List.of(9L)))
        .thenReturn(
            List.of(
                new CollectionJob(
                    1L,
                    9L,
                    13L,
                    CollectionJobType.TRAFFIC,
                    day,
                    CollectionJobStatus.RUNNING,
                    1,
                    null,
                    null,
                    null,
                    sync,
                    null,
                    null,
                    null)));

    var all = service.status(null);
    assertThat(all.repositories()).hasSize(2);
    assertThat(all.lastSyncAt()).isEqualTo(sync);
    assertThat(all.repositories().getFirst().jobs()).hasSize(1);

    assertThat(service.status("Acme").repositories()).hasSize(2);
    assertThat(service.status("other").repositories()).isEmpty();
  }

  @Test
  void anonymousCallersOnlySeePublicTrackedRepositories() {
    when(currentUser.canSeePrivateRepositories()).thenReturn(false);
    when(repositories.tracked()).thenReturn(List.of(publicRepo, privateRepo));
    when(runs.latestPerTrackedRepository()).thenReturn(List.of());
    when(runs.latestCompletedAtForTracked()).thenReturn(null);
    when(jobs.findByRunIds(List.of())).thenReturn(List.of());
    var status = service.status(null);
    assertThat(status.repositories()).extracting(r -> r.fullName()).containsExactly("acme/outbox");
  }
}
