package com.kholodilin.repogrowth.search.planner;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.search.persistence.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchRunJdbcRepository;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Local search planning has no worker any more, so every entry point must fail closed. */
class SearchPlannerTest {

  private static final String MESSAGE = "Collector orchestration is required";

  private final SearchQueryJdbcRepository queryRepository = mock(SearchQueryJdbcRepository.class);
  private final SearchRunJdbcRepository runRepository = mock(SearchRunJdbcRepository.class);
  private final LocalDate date = LocalDate.of(2026, 10, 20);
  private SearchPlanner planner;

  @BeforeEach
  void setUp() {
    planner =
        new SearchPlanner(
            queryRepository,
            runRepository,
            new SearchProperties(
                1, 50, Duration.ofHours(24), 30, new SearchProperties.Activity(30, 180)));
  }

  @Test
  void planAllRefusesToQueueLocalRuns() {
    assertThatThrownBy(() -> planner.planAll(date))
        .isInstanceOf(ApiException.class)
        .hasMessage(MESSAGE);
    assertThatThrownBy(() -> planner.planAll(date, true))
        .isInstanceOf(ApiException.class)
        .hasMessage(MESSAGE);
    verifyNoInteractions(queryRepository, runRepository);
  }

  @Test
  void planRepositoryRefusesToQueueLocalRuns() {
    assertThatThrownBy(() -> planner.planRepository(13L, date))
        .isInstanceOf(ApiException.class)
        .hasMessage(MESSAGE);
    verifyNoInteractions(queryRepository, runRepository);
  }

  @Test
  void planQueryRefusesToQueueALocalRun() {
    assertThatThrownBy(() -> planner.planQuery(5L, 13L, date))
        .isInstanceOf(ApiException.class)
        .hasMessage(MESSAGE);
    verifyNoInteractions(queryRepository, runRepository);
  }
}
