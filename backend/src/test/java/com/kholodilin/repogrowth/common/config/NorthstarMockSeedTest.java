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

  @Test
  void referrerAndPathFormulasStayPositiveAndMatchWireMockBase() {
    assertThat(NorthstarMockSeed.referrerViews(0, 0, 0)).isEqualTo(40);
    assertThat(NorthstarMockSeed.referrerViews(9, 0, 0)).isEqualTo(67);
    assertThat(NorthstarMockSeed.pathViews(0, 0, 0)).isEqualTo(50);
    assertThat(NorthstarMockSeed.pathViews(9, 0, 0)).isEqualTo(86);
    for (int repo = 0; repo < 10; repo++) {
      for (int day = 0; day < NorthstarMockSeed.TRAFFIC_DAYS; day++) {
        for (int referrer = 0; referrer < 5; referrer++) {
          assertThat(NorthstarMockSeed.referrerViews(repo, day, referrer)).isPositive();
          assertThat(NorthstarMockSeed.referrerUniques(repo, day, referrer))
              .isPositive()
              .isLessThanOrEqualTo(NorthstarMockSeed.referrerViews(repo, day, referrer));
        }
        for (int path = 0; path < 4; path++) {
          assertThat(NorthstarMockSeed.pathViews(repo, day, path)).isPositive();
          assertThat(NorthstarMockSeed.pathUniques(repo, day, path))
              .isPositive()
              .isLessThanOrEqualTo(NorthstarMockSeed.pathViews(repo, day, path));
        }
      }
    }
  }

  @Test
  void rankHistoryStaysInsideTopFifty() {
    for (int repo = 0; repo < 10; repo++) {
      for (int day = 0; day < NorthstarMockSeed.TRAFFIC_DAYS; day++) {
        assertThat(NorthstarMockSeed.rankFor(repo, day, 0)).isBetween(1, 50);
        assertThat(NorthstarMockSeed.rankFor(repo, day, 1)).isBetween(1, 50);
      }
    }
    assertThat(NorthstarMockSeed.nearestStarThreshold(73)).isEqualTo(50);
    assertThat(NorthstarMockSeed.nearestStarThreshold(240)).isEqualTo(100);
    assertThat(NorthstarMockSeed.nearestStarThreshold(9)).isEqualTo(10);
  }

  @Test
  void directoryOwnerLoginsAreDeterministicAndUnique() {
    assertThat(NorthstarMockSeed.DIRECTORY_OWNER_COUNT).isEqualTo(30);
    assertThat(NorthstarMockSeed.directoryOwnerLogin(0)).isEqualTo("atlas-01");
    assertThat(NorthstarMockSeed.directoryOwnerLogin(29)).isEqualTo("atlas-30");
    assertThat(
            java.util.stream.IntStream.range(0, NorthstarMockSeed.DIRECTORY_OWNER_COUNT)
                .mapToObj(NorthstarMockSeed::directoryOwnerLogin)
                .distinct()
                .count())
        .isEqualTo(NorthstarMockSeed.DIRECTORY_OWNER_COUNT);
  }

  @Test
  void directoryTopicsRotateAcrossBundles() {
    assertThat(NorthstarMockSeed.directoryTopics(0, 0)).hasSize(3).contains("observability");
    assertThat(NorthstarMockSeed.directoryTopics(1, 0)).isNotEqualTo(NorthstarMockSeed.directoryTopics(0, 0));
    assertThat(NorthstarMockSeed.directoryTopics(0, 1)).hasSize(3);
  }
}
