package com.kholodilin.repogrowth.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.collection.model.CollectionJob;
import com.kholodilin.repogrowth.collection.model.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.model.CollectionJobType;
import com.kholodilin.repogrowth.collection.model.CollectionRun;
import com.kholodilin.repogrowth.collection.model.CollectionRunStatus;
import com.kholodilin.repogrowth.collection.repository.CollectionJobJdbcRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CollectionRunMapperTest {

  @Mock CollectionJobJdbcRepository jobRepository;
  @InjectMocks CollectionRunMapper mapper;

  @Test
  void mapsRunAndJobsToApiViews() {
    Instant now = Instant.parse("2026-11-10T06:00:00Z");
    LocalDate day = LocalDate.of(2026, 11, 10);
    CollectionRun run =
        new CollectionRun(9L, 13L, day, CollectionRunStatus.SUCCESS, 2, 2, 0, now, now);
    CollectionJob job =
        new CollectionJob(
            1L,
            9L,
            13L,
            CollectionJobType.TRAFFIC,
            day,
            CollectionJobStatus.SUCCESS,
            1,
            null,
            null,
            null,
            now,
            now,
            null,
            null);
    when(jobRepository.findByRun(9L)).thenReturn(List.of(job));

    var view = mapper.toView(run);
    assertThat(view.id()).isEqualTo(9L);
    assertThat(view.status()).isEqualTo("SUCCESS");
    assertThat(view.jobs()).hasSize(1);
    assertThat(view.jobs().getFirst().jobType()).isEqualTo("TRAFFIC");
    assertThat(view.jobs().getFirst().status()).isEqualTo("SUCCESS");

    var response = mapper.toResponse(view);
    assertThat(response.id()).isEqualTo(9L);
    assertThat(response.repositoryId()).isEqualTo(13L);
    assertThat(response.businessDate()).isEqualTo("2026-11-10");
    assertThat(response.status()).isEqualTo("SUCCESS");
    assertThat(response.plannedJobs()).isEqualTo(2);
    assertThat(response.successfulJobs()).isEqualTo(2);
    assertThat(response.failedJobs()).isZero();
    assertThat(response.createdAt()).isEqualTo(now);
    assertThat(response.completedAt()).isEqualTo(now);
    assertThat(response.jobs()).hasSize(1);
    assertThat(response.jobs().getFirst().id()).isEqualTo(1L);
    assertThat(response.jobs().getFirst().jobType()).isEqualTo("TRAFFIC");
    assertThat(response.jobs().getFirst().status()).isEqualTo("SUCCESS");
    assertThat(response.jobs().getFirst().attempt()).isEqualTo(1);
    assertThat(response.jobs().getFirst().errorCode()).isNull();
    assertThat(response.jobs().getFirst().errorMessage()).isNull();
    assertThat(response.jobs().getFirst().startedAt()).isEqualTo(now);
    assertThat(response.jobs().getFirst().completedAt()).isEqualTo(now);
  }
}
