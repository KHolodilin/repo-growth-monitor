package com.kholodilin.repogrowth.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.kholodilin.repogrowth.common.model.period.Period;
import com.kholodilin.repogrowth.search.application.BandKpiMath.DayMode;
import com.kholodilin.repogrowth.search.application.BandKpiMath.RankPoint;
import com.kholodilin.repogrowth.search.application.BandKpiMath.RankSample;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BandKpiMathTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

  @Test
  void strictDayRequiresEveryTrackedQueryOnTheDay() {
    RankSample a =
        sample(
            1,
            true,
            2,
            List.of(
                new RankPoint(TODAY.minusDays(2), 2),
                new RankPoint(TODAY.minusDays(1), 2),
                new RankPoint(TODAY, 2)));
    RankSample b =
        sample(
            2,
            true,
            4,
            List.of(new RankPoint(TODAY.minusDays(2), 4), new RankPoint(TODAY.minusDays(1), 4)));
    var kpi = BandKpiMath.bandKpi(List.of(a, b), 3, Period.D7, TODAY, DayMode.STRICT_DAY);
    assertThat(kpi.value()).isEqualTo(1);
    assertThat(kpi.series()).hasSize(7);
    assertThat(kpi.series().get(kpi.series().size() - 1)).isNull();
    assertThat(kpi.series().get(kpi.series().size() - 2)).isEqualTo(1);
  }

  @Test
  void carryForwardKeepsPreviousRankAcrossGaps() {
    RankSample topic =
        sample(
            7,
            true,
            5,
            List.of(
                new RankPoint(TODAY.minusDays(10), 8),
                new RankPoint(TODAY.minusDays(3), 8),
                new RankPoint(TODAY.minusDays(1), 5)));
    var kpi = BandKpiMath.bandKpi(List.of(topic), 10, Period.D7, TODAY, DayMode.CARRY_FORWARD);
    assertThat(kpi.value()).isEqualTo(1);
    assertThat(kpi.series().get(kpi.series().size() - 1)).isEqualTo(1);
    assertThat(kpi.absoluteChange()).isZero();
  }

  @Test
  void emptyCarryForwardCurrentIsZeroAndAllTimeHasNoComparison() {
    assertThat(BandKpiMath.bandKpi(List.of(), 3, Period.D30, TODAY, DayMode.CARRY_FORWARD).value())
        .isZero();
    RankSample onlyToday = sample(1, true, 1, List.of(new RankPoint(TODAY, 1)));
    var all = BandKpiMath.bandKpi(List.of(onlyToday), 3, Period.ALL, TODAY, DayMode.STRICT_DAY);
    assertThat(all.absoluteChange()).isNull();
    assertThat(all.growthPercent()).isNull();
    assertThat(all.series()).isNotEmpty();
  }

  @Test
  void oneDayWindowComparesAgainstPreviousCalendarDay() {
    RankSample sample =
        sample(
            1,
            true,
            1,
            List.of(new RankPoint(TODAY.minusDays(2), 5), new RankPoint(TODAY.minusDays(1), 2)));
    var kpi = BandKpiMath.bandKpi(List.of(sample), 3, Period.D1, TODAY, DayMode.STRICT_DAY);
    assertThat(kpi.value()).isEqualTo(1);
    assertThat(kpi.series()).containsExactly(1);
    assertThat(kpi.absoluteChange()).isEqualTo(1);
  }

  @Test
  void disabledQueriesAreIgnoredInStrictModeOnly() {
    RankSample disabled = sample(1, false, 1, List.of(new RankPoint(TODAY, 1)));
    RankSample enabled = sample(2, true, 2, List.of(new RankPoint(TODAY, 2)));
    assertThat(
            BandKpiMath.bandKpi(List.of(disabled, enabled), 3, Period.D7, TODAY, DayMode.STRICT_DAY)
                .value())
        .isEqualTo(1);
    assertThat(
            BandKpiMath.bandKpi(
                    List.of(disabled, enabled), 3, Period.D7, TODAY, DayMode.CARRY_FORWARD)
                .value())
        .isEqualTo(2);
  }

  @Test
  void usageClampsProgressAndAvailableSlots() {
    assertThat(BandKpiMath.usage(12, 50)).isEqualTo(new BandKpiMath.Usage(12, 50, 38, 24.0));
    assertThat(BandKpiMath.usage(-2, 10).used()).isZero();
    assertThat(BandKpiMath.usage(4, 0).progress()).isZero();
    assertThat(BandKpiMath.usage(120, 50).progress()).isEqualTo(100.0);
  }

  @Test
  void longerPeriodsResolveWindows() {
    RankSample sample =
        sample(
            1,
            true,
            1,
            List.of(
                new RankPoint(TODAY.minusDays(40), 1),
                new RankPoint(TODAY.minusDays(1), 1),
                new RankPoint(TODAY, 1)));
    assertThat(
            BandKpiMath.bandKpi(List.of(sample), 3, Period.D90, TODAY, DayMode.STRICT_DAY).series())
        .hasSize(90);
    assertThat(
            BandKpiMath.bandKpi(List.of(sample), 3, Period.Y1, TODAY, DayMode.STRICT_DAY).series())
        .hasSize(365);
    assertThat(
            BandKpiMath.bandKpi(List.of(sample), 3, Period.D30, TODAY, DayMode.STRICT_DAY).series())
        .hasSize(30);
  }

  @Test
  void samplesWithoutPointsYieldNullCurrentInStrictMode() {
    RankSample empty = sample(1, true, null, List.of());
    assertThat(BandKpiMath.bandKpi(List.of(empty), 3, Period.D7, TODAY, DayMode.STRICT_DAY).value())
        .isNull();
  }

  private static RankSample sample(
      long id, boolean enabled, Integer currentRank, List<RankPoint> points) {
    return new RankSample(id, enabled, currentRank, points);
  }
}
