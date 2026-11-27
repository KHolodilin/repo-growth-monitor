package com.kholodilin.repogrowth.topics.planner;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.topics.repository.TopicRunJdbcRepository;
import com.kholodilin.repogrowth.topics.repository.TopicWatchJdbcRepository;
import com.kholodilin.repogrowth.topics.service.TopicWatchSync;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Local topic planning has no worker any more, so every entry point must fail closed. */
class TopicPlannerTest {

  private static final String MESSAGE = "Collector orchestration is required";

  private final TopicWatchJdbcRepository watchRepository = mock(TopicWatchJdbcRepository.class);
  private final TopicRunJdbcRepository runRepository = mock(TopicRunJdbcRepository.class);
  private final TopicWatchSync topicWatchSync = mock(TopicWatchSync.class);
  private final LocalDate date = LocalDate.of(2026, 9, 23);
  private TopicPlanner planner;

  @BeforeEach
  void setUp() {
    planner =
        new TopicPlanner(
            watchRepository,
            runRepository,
            topicWatchSync,
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
    verifyNoInteractions(watchRepository, runRepository, topicWatchSync);
  }

  @Test
  void planRepositoryRefusesToQueueLocalRuns() {
    assertThatThrownBy(() -> planner.planRepository(13L, date))
        .isInstanceOf(ApiException.class)
        .hasMessage(MESSAGE);
    verifyNoInteractions(watchRepository, runRepository, topicWatchSync);
  }

  @Test
  void planWatchRefusesToQueueALocalRun() {
    assertThatThrownBy(() -> planner.planWatch(7L, 13L, date))
        .isInstanceOf(ApiException.class)
        .hasMessage(MESSAGE);
    verifyNoInteractions(watchRepository, runRepository, topicWatchSync);
  }
}
