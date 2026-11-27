package com.kholodilin.repogrowth.stats.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.stats.service.StatsReadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StatsControllerTest {

  @Mock StatsReadService stats;
  @InjectMocks StatsController controller;

  @Test
  void delegatesEveryStatsResourceRead() {
    when(stats.ownerKpi(1L, "7d")).thenReturn(null);
    when(stats.repositoryKpi(13L, "7d")).thenReturn(null);
    when(stats.ownerHistoryResponse(1L, "7d")).thenReturn(null);
    when(stats.repositoryHistoryResponse(13L, "7d")).thenReturn(null);
    controller.ownerStatsKpi(1L, "7d");
    controller.repositoryStatsKpi(13L, "7d");
    controller.ownerStatsHistory(1L, "7d");
    controller.repositoryStatsHistory(13L, "7d");
    verify(stats).repositoryHistoryResponse(13L, "7d");
  }
}
