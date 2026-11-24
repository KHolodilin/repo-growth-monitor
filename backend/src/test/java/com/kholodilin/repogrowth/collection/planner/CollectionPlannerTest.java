package com.kholodilin.repogrowth.collection.planner;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CollectionPlannerTest {
  private static final String MESSAGE = "Collector orchestration is required";
  private CollectionPlanner planner;
  private final LocalDate date = LocalDate.of(2026, 10, 20);

  @BeforeEach
  void setUp() {
    planner =
        new CollectionPlanner(
            mock(RepositoryJdbcRepository.class),
            mock(CollectionRunJdbcRepository.class),
            mock(CollectionJobJdbcRepository.class),
            mock(TrafficJdbcRepository.class));
  }

  @Test
  void everyEntryPointFailsClosed() {
    assertThatThrownBy(() -> planner.planAll(date))
        .isInstanceOf(ApiException.class)
        .hasMessage(MESSAGE);
    assertThatThrownBy(() -> planner.planAll(date, true))
        .isInstanceOf(ApiException.class)
        .hasMessage(MESSAGE);
    assertThatThrownBy(() -> planner.planRepository(1L, date))
        .isInstanceOf(ApiException.class)
        .hasMessage(MESSAGE);
    assertThatThrownBy(() -> planner.planRepository(1L, date, true))
        .isInstanceOf(ApiException.class)
        .hasMessage(MESSAGE);
    assertThatThrownBy(() -> planner.planRepository(1L, date, false, true))
        .isInstanceOf(ApiException.class)
        .hasMessage(MESSAGE);
  }
}
