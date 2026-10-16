package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.analytics.application.PortfolioHighlightMath.PromotionCount;
import com.kholodilin.repogrowth.analytics.application.PortfolioHighlightMath.RankPoint;
import com.kholodilin.repogrowth.analytics.application.PortfolioHighlightMath.VisitorRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioHighlightMathTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);

    @Test
    void picksTheStrongestRiseAndTheWorstDrop() {
        List<RankPoint> points = List.of(
                new RankPoint(1, "acme/outbox", "outbox", LocalDate.of(2026, 9, 20), 22),
                new RankPoint(1, "acme/outbox", "outbox", LocalDate.of(2026, 10, 8), 14),
                new RankPoint(2, "acme/kafka", "kafka", LocalDate.of(2026, 9, 20), 4),
                new RankPoint(2, "acme/kafka", "kafka", LocalDate.of(2026, 10, 8), 11),
                new RankPoint(1, "acme/outbox", "flat", LocalDate.of(2026, 9, 20), 5),
                new RankPoint(1, "acme/outbox", "flat", LocalDate.of(2026, 10, 8), 5),
                new RankPoint(3, "acme/other", "small", LocalDate.of(2026, 10, 2), 10),
                new RankPoint(3, "acme/other", "small", LocalDate.of(2026, 10, 8), 9)
        );

        assertThat(PortfolioHighlightMath.strongestRise(points, FROM)).satisfies(rise -> {
            assertThat(rise.fullName()).isEqualTo("acme/outbox");
            assertThat(rise.queryName()).isEqualTo("outbox");
            assertThat(rise.fromRank()).isEqualTo(22);
            assertThat(rise.toRank()).isEqualTo(14);
        });
        assertThat(PortfolioHighlightMath.worstDrop(points, FROM).queryName()).isEqualTo("kafka");
        assertThat(PortfolioHighlightMath.worstDrop(points, FROM).fromRank()).isEqualTo(4);
        assertThat(PortfolioHighlightMath.worstDrop(points, FROM).toRank()).isEqualTo(11);
    }

    @Test
    void breaksEqualMovesByTheFinishAndTheQueryName() {
        List<RankPoint> points = List.of(
                new RankPoint(1, "acme/a", "zeta", FROM.plusDays(1), 30),
                new RankPoint(1, "acme/a", "zeta", FROM.plusDays(2), 22),
                new RankPoint(2, "acme/b", "alpha", FROM.plusDays(1), 22),
                new RankPoint(2, "acme/b", "alpha", FROM.plusDays(2), 14),
                new RankPoint(3, "acme/c", "mid", FROM.plusDays(1), 22),
                new RankPoint(3, "acme/c", "mid", FROM.plusDays(2), 14),
                new RankPoint(4, "acme/d", "down-z", FROM.plusDays(1), 2),
                new RankPoint(4, "acme/d", "down-z", FROM.plusDays(2), 9),
                new RankPoint(5, "acme/e", "down-a", FROM.plusDays(1), 4),
                new RankPoint(5, "acme/e", "down-a", FROM.plusDays(2), 11),
                new RankPoint(6, "acme/f", "down-b", FROM.plusDays(1), 4),
                new RankPoint(6, "acme/f", "down-b", FROM.plusDays(2), 11)
        );
        assertThat(PortfolioHighlightMath.strongestRise(points, FROM).queryName()).isEqualTo("alpha");
        assertThat(PortfolioHighlightMath.worstDrop(points, FROM).queryName()).isEqualTo("down-a");
    }

    @Test
    void ignoresAQueryThatNeverMovesInsideTheWindow() {
        List<RankPoint> points = List.of(new RankPoint(1, "acme/outbox", "outbox", LocalDate.of(2026, 10, 8), 14));
        assertThat(PortfolioHighlightMath.strongestRise(points, FROM)).isNull();
        assertThat(PortfolioHighlightMath.worstDrop(List.of(), FROM)).isNull();
    }

    @Test
    void countsPromotionEventsAndNamesEveryRepository() {
        assertThat(PortfolioHighlightMath.promotionEvents(List.of())).isNull();
        assertThat(PortfolioHighlightMath.promotionEvents(List.of(
                new PromotionCount(2, "acme/kafka", 1),
                new PromotionCount(1, "acme/outbox", 2)
        ))).satisfies(events -> {
            assertThat(events.count()).isEqualTo(3);
            assertThat(events.repositoryId()).isEqualTo(1);
            assertThat(events.repositoryNames()).containsExactly("acme/kafka", "acme/outbox");
        });
        assertThat(PortfolioHighlightMath.promotionEvents(List.of(
                new PromotionCount(2, "acme/b", 2),
                new PromotionCount(1, "acme/a", 2)
        )).fullName()).isEqualTo("acme/a");
    }

    @Test
    void picksTheRepositoryWithTheStrongestVisitorGrowth() {
        assertThat(PortfolioHighlightMath.strongestVisitorGrowth(List.of(
                new VisitorRow(1, "acme/flat", 10L, null),
                new VisitorRow(2, "acme/up", 4L, 20.0),
                new VisitorRow(3, "acme/more", 9L, 20.0)
        )).fullName()).isEqualTo("acme/more");
        assertThat(PortfolioHighlightMath.strongestVisitorGrowth(List.of(
                new VisitorRow(1, "acme/none", 1L, null)
        ))).isNull();
        assertThat(PortfolioHighlightMath.strongestVisitorGrowth(List.of(
                new VisitorRow(2, "acme/b", 3L, 5.0),
                new VisitorRow(1, "acme/a", 3L, 5.0)
        )).fullName()).isEqualTo("acme/a");
        assertThat(PortfolioHighlightMath.strongestVisitorGrowth(List.of())).isNull();
    }

    @Test
    void keepsTheEarlierChoiceWhenALaterCandidateIsNotBetter() throws Exception {
        var constructor = PortfolioHighlightMath.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();

        List<RankPoint> points = List.of(
                new RankPoint(9, "acme/old", "old", FROM.minusDays(2), 3),
                new RankPoint(1, "acme/first", "first-rise", FROM.plusDays(1), 20),
                new RankPoint(1, "acme/first", "first-rise", FROM.plusDays(2), 12),
                new RankPoint(2, "acme/big", "bigger", FROM.plusDays(1), 30),
                new RankPoint(2, "acme/big", "bigger", FROM.plusDays(2), 10),
                new RankPoint(3, "acme/worse", "worse-finish", FROM.plusDays(1), 40),
                new RankPoint(3, "acme/worse", "worse-finish", FROM.plusDays(2), 20),
                new RankPoint(4, "acme/z", "z-same", FROM.plusDays(1), 30),
                new RankPoint(4, "acme/z", "z-same", FROM.plusDays(2), 10),
                new RankPoint(5, "acme/a", "a-same", FROM.plusDays(1), 30),
                new RankPoint(5, "acme/a", "a-same", FROM.plusDays(2), 10),
                new RankPoint(6, "acme/drop", "first-drop", FROM.plusDays(1), 10),
                new RankPoint(6, "acme/drop", "first-drop", FROM.plusDays(2), 15),
                new RankPoint(7, "acme/drop", "deeper", FROM.plusDays(1), 10),
                new RankPoint(7, "acme/drop", "deeper", FROM.plusDays(2), 20),
                new RankPoint(8, "acme/drop", "shallower", FROM.plusDays(1), 5),
                new RankPoint(8, "acme/drop", "shallower", FROM.plusDays(2), 15),
                new RankPoint(10, "acme/drop", "z-drop", FROM.plusDays(1), 10),
                new RankPoint(10, "acme/drop", "z-drop", FROM.plusDays(2), 20),
                new RankPoint(11, "acme/drop", "a-drop", FROM.plusDays(1), 10),
                new RankPoint(11, "acme/drop", "a-drop", FROM.plusDays(2), 20)
        );
        assertThat(PortfolioHighlightMath.strongestRise(points, FROM).queryName()).isEqualTo("a-same");
        assertThat(PortfolioHighlightMath.worstDrop(points, FROM).queryName()).isEqualTo("a-drop");

        assertThat(PortfolioHighlightMath.promotionEvents(List.of(
                new PromotionCount(1, "acme/a", 3),
                new PromotionCount(2, "acme/m", 1),
                new PromotionCount(3, "acme/z", 3)
        ))).satisfies(events -> {
            assertThat(events.fullName()).isEqualTo("acme/a");
            assertThat(events.count()).isEqualTo(7);
            assertThat(events.repositoryNames()).containsExactly("acme/a", "acme/m", "acme/z");
        });

        assertThat(PortfolioHighlightMath.strongestVisitorGrowth(List.of(
                new VisitorRow(1, "mid", null, 10.0),
                new VisitorRow(2, "high", 5L, 40.0),
                new VisitorRow(3, "low", 9L, 1.0),
                new VisitorRow(4, "fewer", 1L, 40.0),
                new VisitorRow(5, "z-name", 5L, 40.0),
                new VisitorRow(6, "a-name", 5L, 40.0)
        )).fullName()).isEqualTo("a-name");
    }
}
