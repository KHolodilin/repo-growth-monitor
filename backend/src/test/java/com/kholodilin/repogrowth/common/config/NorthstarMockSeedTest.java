package com.kholodilin.repogrowth.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NorthstarMockSeedTest {

  @Test
  void trafficFormulasStayPositiveAndGrowAcrossDays() {
    assertThat(NorthstarMockSeed.TRAFFIC_DAYS).isEqualTo(30);
    assertThat(NorthstarMockSeed.OWNER_LOGIN).isEqualTo("northstar");
    assertThat(NorthstarMockSeed.viewsFor(0, 0)).isEqualTo(40);
    assertThat(NorthstarMockSeed.viewsFor(0, 29)).isEqualTo(40 + 29 * 3);
    assertThat(NorthstarMockSeed.viewsFor(1, 0)).isEqualTo(59);
    assertThat(NorthstarMockSeed.clonesFor(0, 0)).isEqualTo(2);
    assertThat(NorthstarMockSeed.clonesFor(2, 4)).isEqualTo(8);
    for (int repo = 0; repo < 10; repo++) {
      for (int day = 0; day < NorthstarMockSeed.TRAFFIC_DAYS; day++) {
        assertThat(NorthstarMockSeed.viewsFor(repo, day)).isPositive();
        assertThat(NorthstarMockSeed.clonesFor(repo, day)).isPositive();
      }
    }
  }
}
