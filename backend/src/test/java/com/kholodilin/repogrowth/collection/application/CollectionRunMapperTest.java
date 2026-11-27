package com.kholodilin.repogrowth.collection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.domain.CollectionRunStatus;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
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
  }
}
