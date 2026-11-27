package com.kholodilin.repogrowth.traffic.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.traffic.application.TrafficReadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrafficControllerTest {

  @Mock TrafficReadService traffic;
  @InjectMocks TrafficController controller;

  @Test
  void delegatesEveryTrafficResourceRead() {
    when(traffic.ownerKpi(1L, "7d")).thenReturn(null);
    when(traffic.repositoryKpi(13L, "7d")).thenReturn(null);
    when(traffic.ownerDailyHistory(1L, "7d")).thenReturn(null);
    when(traffic.dailyHistory(13L, "7d")).thenReturn(null);
    when(traffic.latestReferrers(13L)).thenReturn(null);
    when(traffic.latestPaths(13L, true)).thenReturn(null);
    when(traffic.referrerHistory(13L, "7d", null)).thenReturn(null);
    when(traffic.pathHistory(13L, "7d", null, false)).thenReturn(null);

    controller.ownerTrafficKpi(1L, "7d");
    controller.repositoryTrafficKpi(13L, "7d");
    controller.ownerTrafficHistory(1L, "7d");
    controller.repositoryTrafficHistory(13L, "7d");
    controller.referrers(13L);
    controller.paths(13L, true);
    controller.referrerHistory(13L, "7d", null);
    controller.pathHistory(13L, "7d", null, false);

    verify(traffic).pathHistory(13L, "7d", null, false);
  }
}
