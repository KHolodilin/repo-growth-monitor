package com.kholodilin.repogrowth.traffic;

import com.kholodilin.repogrowth.traffic.SnapshotPeriodMath.AggregatedRow;
import com.kholodilin.repogrowth.traffic.SnapshotPeriodMath.DayTraffic;
import com.kholodilin.repogrowth.traffic.SnapshotPeriodMath.Observation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotPeriodMathTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 28);

    @Test
    void coveringSnapshotPrefersTheEarliestSnapshotThatStillContainsTheDay() {
        TreeSet<LocalDate> dates = new TreeSet<>(List.of(TODAY.minusDays(6), TODAY));
        assertThat(SnapshotPeriodMath.coveringSnapshot(TODAY.minusDays(6), dates)).isEqualTo(TODAY.minusDays(6));
        assertThat(SnapshotPeriodMath.coveringSnapshot(TODAY.minusDays(5), dates)).isEqualTo(TODAY);
        assertThat(SnapshotPeriodMath.coveringSnapshot(TODAY.minusDays(20), dates)).isNull();
    }

    @Test
    void scalesFourteenDaySnapshotToSelectedDaysUsingTrafficWeights() {
        List<Observation> snapshots = List.of(
                new Observation(TODAY, "github.com", null, 81, 3),
                new Observation(TODAY, "mvnrepository.com", null, 1, 1)
        );
        List<DayTraffic> traffic = List.of(
                new DayTraffic(TODAY.minusDays(15), 20, 1),
                new DayTraffic(TODAY.minusDays(14), 3, 1),
                new DayTraffic(TODAY.minusDays(9), 2, 2),
                new DayTraffic(TODAY.minusDays(8), 1, 1),
                new DayTraffic(TODAY.minusDays(7), 1, 1),
                new DayTraffic(TODAY.minusDays(5), 1, 1),
                new DayTraffic(TODAY.minusDays(4), 37, 2),
                new DayTraffic(TODAY.minusDays(3), 22, 1),
                new DayTraffic(TODAY.minusDays(2), 6, 1),
                new DayTraffic(TODAY.minusDays(1), 6, 1)
        );

        List<AggregatedRow> sevenDays = SnapshotPeriodMath.aggregate(
                TODAY.minusDays(6),
                TODAY,
                snapshots,
                traffic
        );
        assertThat(sevenDays).containsExactly(
                new AggregatedRow("github.com", null, 77, 2),
                new AggregatedRow("mvnrepository.com", null, 1, 1)
        );

        List<AggregatedRow> thirtyDays = SnapshotPeriodMath.aggregate(
                TODAY.minusDays(29),
                TODAY,
                snapshots,
                traffic
        );
        assertThat(thirtyDays).containsExactly(
                new AggregatedRow("github.com", null, 81, 3),
                new AggregatedRow("mvnrepository.com", null, 1, 1)
        );
    }

    @Test
    void doesNotSumRawRollingSnapshotsAcrossDays() {
        LocalDate earlier = TODAY.minusDays(2);
        List<Observation> snapshots = List.of(
                new Observation(earlier, "github.com", null, 110, 40),
                new Observation(TODAY, "github.com", null, 200, 80)
        );
        List<DayTraffic> traffic = new java.util.ArrayList<>();
        for (int i = 20; i >= 0; i--) {
            traffic.add(new DayTraffic(TODAY.minusDays(i), 1, 1));
        }

        List<AggregatedRow> sevenDays = SnapshotPeriodMath.aggregate(
                TODAY.minusDays(6),
                TODAY,
                snapshots,
                traffic
        );
        assertThat(sevenDays).containsExactly(new AggregatedRow("github.com", null, 68, 26));
    }
}
