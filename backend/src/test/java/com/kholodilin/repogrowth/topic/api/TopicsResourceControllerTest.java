package com.kholodilin.repogrowth.topic.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.auth.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.publication.PublicationService;
import com.kholodilin.repogrowth.topic.application.TopicCompetitorService;
import com.kholodilin.repogrowth.topic.application.TopicVisibilityService;
import com.kholodilin.repogrowth.topic.application.TopicVisibilityService.TopicHistory;
import com.kholodilin.repogrowth.topic.application.TopicVisibilityService.TopicRunResults;
import com.kholodilin.repogrowth.topic.application.TopicsKpiService;
import com.kholodilin.repogrowth.topic.domain.TopicWatch;
import com.kholodilin.repogrowth.topic.persistence.TopicWatchJdbcRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TopicsResourceControllerTest {

  @Mock ResourceAccessPolicy access;
  @Mock TopicsKpiService kpi;
  @Mock TopicVisibilityService topics;
  @Mock TopicCompetitorService topicCompetitors;
  @Mock TopicWatchJdbcRepository watches;

  TopicsResourceController controller;
  TopicWatch watch;

  @BeforeEach
  void setUp() {
    controller = new TopicsResourceController(access, kpi, topics, topicCompetitors, watches);
    Instant now = Instant.parse("2026-11-10T06:00:00Z");
    watch = new TopicWatch(7L, 13L, "outbox", null, "stars", "desc", true, 50, now, now);
  }

  @Test
  void delegatesKpiWatchesAndCompetitors() {
    when(kpi.ownerKpi(1L, "7d", "all")).thenReturn(null);
    when(kpi.repositoryKpi(13L, "7d", "language")).thenReturn(null);
    controller.ownerTopicsKpi(1L, "7d", "all");
    controller.repositoryTopicsKpi(13L, "7d", "language");
    when(kpi.repositoryWatchesWithHistory(13L, "all", true, 20)).thenReturn(List.of());
    assertThat(controller.listWatches(13L, "all", "history", 20)).isEmpty();

    when(watches.findById(7L)).thenReturn(Optional.of(watch));
    doNothing().when(access).requireReadable(13L, PublicationService.Module.TOPICS);
    assertThat(controller.get(7L)).isEqualTo(watch);
    when(topics.history(7L))
        .thenReturn(
            new TopicHistory(
                watch, 5, null, null, null, 5, List.of(), Instant.now(), "SUCCESS", 1, List.of()));
    assertThat(controller.history(7L).watch()).isEqualTo(watch);
    when(topics.latestResults(7L)).thenReturn(new TopicRunResults(null, watch, List.of()));
    assertThat(controller.results(7L).watch()).isEqualTo(watch);
    when(topicCompetitors.series(anyLong(), anyString(), anyString())).thenReturn(List.of());
    assertThat(controller.series(7L)).isEmpty();
    when(topicCompetitors.card(anyLong(), anyString(), anyString(), anyLong())).thenReturn(null);
    controller.competitor(7L, 99L);
    when(topics.results(3L)).thenReturn(new TopicRunResults(null, watch, List.of()));
    assertThat(controller.runResults(3L).watch()).isEqualTo(watch);
  }

  @Test
  void missingWatchIsNotFound() {
    when(watches.findById(9L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> controller.get(9L)).isInstanceOf(ApiException.class);
  }
}
