package com.kholodilin.repogrowth.traffic.persistence;

import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.traffic.SnapshotHistoryMath.Observation;
import com.kholodilin.repogrowth.traffic.domain.TrafficDaily;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class TrafficJdbcRepository {

    private static final RowMapper<RepositoryDailyStats> DAILY_STATS_MAPPER = (rs, rowNum) -> new RepositoryDailyStats(
            rs.getObject("stat_date", LocalDate.class),
            rs.getInt("stars"),
            rs.getInt("watchers"),
            rs.getInt("forks"),
            rs.getInt("contributors")
    );

    private static final RowMapper<TrafficDaily> TRAFFIC_MAPPER = (rs, rowNum) -> new TrafficDaily(
            rs.getLong("id"),
            rs.getLong("repository_id"),
            rs.getObject("traffic_date", LocalDate.class),
            rs.getInt("views"),
            rs.getInt("unique_visitors"),
            rs.getInt("clones"),
            rs.getInt("unique_cloners")
    );

    private final JdbcClient jdbcClient;

    public TrafficJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void upsertDaily(
            long repositoryId,
            LocalDate date,
            int views,
            int uniqueVisitors,
            int clones,
            int uniqueCloners
    ) {
        jdbcClient.sql("""
                        INSERT INTO traffic_daily (
                            repository_id, traffic_date, views, unique_visitors, clones, unique_cloners
                        ) VALUES (
                            :repositoryId, :trafficDate, :views, :uniqueVisitors, :clones, :uniqueCloners
                        )
                        ON CONFLICT (repository_id, traffic_date) DO UPDATE SET
                            views = EXCLUDED.views,
                            unique_visitors = EXCLUDED.unique_visitors,
                            clones = EXCLUDED.clones,
                            unique_cloners = EXCLUDED.unique_cloners,
                            updated_at = NOW()
                        """)
                .param("repositoryId", repositoryId)
                .param("trafficDate", date)
                .param("views", views)
                .param("uniqueVisitors", uniqueVisitors)
                .param("clones", clones)
                .param("uniqueCloners", uniqueCloners)
                .update();
    }

    public void upsertDailyStats(
            long repositoryId,
            LocalDate date,
            int stars,
            int watchers,
            int forks,
            int openIssues,
            int contributors
    ) {
        jdbcClient.sql("""
                        INSERT INTO repository_daily_stats (
                            repository_id, stat_date, stars, watchers, forks, open_issues, contributors
                        )
                        VALUES (
                            :repositoryId, :statDate, :stars, :watchers, :forks, :openIssues, :contributors
                        )
                        ON CONFLICT (repository_id, stat_date) DO UPDATE SET
                            stars = EXCLUDED.stars,
                            watchers = EXCLUDED.watchers,
                            forks = EXCLUDED.forks,
                            open_issues = EXCLUDED.open_issues,
                            contributors = EXCLUDED.contributors
                        """)
                .param("repositoryId", repositoryId)
                .param("statDate", date)
                .param("stars", stars)
                .param("watchers", watchers)
                .param("forks", forks)
                .param("openIssues", openIssues)
                .param("contributors", contributors)
                .update();
    }

    public List<RepositoryDailyStats> dailyStatsHistory(long repositoryId, LocalDate fromInclusive) {
        if (fromInclusive == null) {
            return jdbcClient.sql("""
                            SELECT stat_date, stars, watchers, forks, contributors
                            FROM repository_daily_stats
                            WHERE repository_id = :repositoryId
                            ORDER BY stat_date
                            """)
                    .param("repositoryId", repositoryId)
                    .query(DAILY_STATS_MAPPER)
                    .list();
        }
        return jdbcClient.sql("""
                        SELECT stat_date, stars, watchers, forks, contributors
                        FROM repository_daily_stats
                        WHERE repository_id = :repositoryId AND stat_date >= :fromDate
                        ORDER BY stat_date
                        """)
                .param("repositoryId", repositoryId)
                .param("fromDate", fromInclusive)
                .query(DAILY_STATS_MAPPER)
                .list();
    }

    public Optional<LocalDate> earliestDailyStatsDate(long repositoryId) {
        return jdbcClient.sql("""
                        SELECT MIN(stat_date)
                        FROM repository_daily_stats
                        WHERE repository_id = :repositoryId
                        """)
                .param("repositoryId", repositoryId)
                .query(LocalDate.class)
                .optional()
                .filter(date -> date != null);
    }

    /**
     * A day holds one row per referrer. A repeated collection clears the day first, so the numbers
     * are replaced instead of appended and a plain sum over the table stays meaningful.
     */
    public void deleteReferrerSnapshot(long repositoryId, LocalDate snapshotDate) {
        jdbcClient.sql("""
                        DELETE FROM traffic_referrer_snapshot
                        WHERE repository_id = :repositoryId AND snapshot_date = :snapshotDate
                        """)
                .param("repositoryId", repositoryId)
                .param("snapshotDate", snapshotDate)
                .update();
    }

    public void deletePathSnapshot(long repositoryId, LocalDate snapshotDate) {
        jdbcClient.sql("""
                        DELETE FROM traffic_path_snapshot
                        WHERE repository_id = :repositoryId AND snapshot_date = :snapshotDate
                        """)
                .param("repositoryId", repositoryId)
                .param("snapshotDate", snapshotDate)
                .update();
    }

    public void insertReferrers(
            long repositoryId,
            LocalDate snapshotDate,
            Instant snapshotAt,
            String referrer,
            int views,
            int uniqueVisitors
    ) {
        jdbcClient.sql("""
                        INSERT INTO traffic_referrer_snapshot (
                            repository_id, snapshot_date, snapshot_at, referrer, views, unique_visitors
                        ) VALUES (
                            :repositoryId, :snapshotDate, :snapshotAt, :referrer, :views, :uniqueVisitors
                        )
                        """)
                .param("repositoryId", repositoryId)
                .param("snapshotDate", snapshotDate)
                .param("snapshotAt", SqlTime.ts(snapshotAt))
                .param("referrer", referrer)
                .param("views", views)
                .param("uniqueVisitors", uniqueVisitors)
                .update();
    }

    public void insertPath(
            long repositoryId,
            LocalDate snapshotDate,
            Instant snapshotAt,
            String path,
            String title,
            int views,
            int uniqueVisitors,
            boolean servicePath
    ) {
        jdbcClient.sql("""
                        INSERT INTO traffic_path_snapshot (
                            repository_id, snapshot_date, snapshot_at, path, title, views, unique_visitors, service_path
                        ) VALUES (
                            :repositoryId, :snapshotDate, :snapshotAt, :path, :title, :views, :uniqueVisitors, :servicePath
                        )
                        """)
                .param("repositoryId", repositoryId)
                .param("snapshotDate", snapshotDate)
                .param("snapshotAt", SqlTime.ts(snapshotAt))
                .param("path", path)
                .param("title", title)
                .param("views", views)
                .param("uniqueVisitors", uniqueVisitors)
                .param("servicePath", servicePath)
                .update();
    }

    public List<TrafficDaily> history(long repositoryId, LocalDate fromInclusive) {
        if (fromInclusive == null) {
            return jdbcClient.sql("""
                            SELECT * FROM traffic_daily
                            WHERE repository_id = :repositoryId
                            ORDER BY traffic_date
                            """)
                    .param("repositoryId", repositoryId)
                    .query(TRAFFIC_MAPPER)
                    .list();
        }
        return jdbcClient.sql("""
                        SELECT * FROM traffic_daily
                        WHERE repository_id = :repositoryId AND traffic_date >= :fromDate
                        ORDER BY traffic_date
                        """)
                .param("repositoryId", repositoryId)
                .param("fromDate", fromInclusive)
                .query(TRAFFIC_MAPPER)
                .list();
    }

    public Optional<LocalDate> latestDate(long repositoryId) {
        return jdbcClient.sql("""
                        SELECT MAX(traffic_date)
                        FROM traffic_daily
                        WHERE repository_id = :repositoryId
                        """)
                .param("repositoryId", repositoryId)
                .query(LocalDate.class)
                .optional()
                .filter(date -> date != null);
    }

    public TrafficTotals totals(long repositoryId, LocalDate fromInclusive) {
        if (fromInclusive == null) {
            return jdbcClient.sql("""
                            SELECT COALESCE(SUM(views), 0) AS views,
                                   COALESCE(SUM(unique_visitors), 0) AS unique_visitors,
                                   COALESCE(SUM(clones), 0) AS clones,
                                   COALESCE(SUM(unique_cloners), 0) AS unique_cloners
                            FROM traffic_daily
                            WHERE repository_id = :repositoryId
                            """)
                    .param("repositoryId", repositoryId)
                    .query((rs, rowNum) -> new TrafficTotals(
                            rs.getLong("views"),
                            rs.getLong("unique_visitors"),
                            rs.getLong("clones"),
                            rs.getLong("unique_cloners")
                    ))
                    .single();
        }
        return jdbcClient.sql("""
                        SELECT COALESCE(SUM(views), 0) AS views,
                               COALESCE(SUM(unique_visitors), 0) AS unique_visitors,
                               COALESCE(SUM(clones), 0) AS clones,
                               COALESCE(SUM(unique_cloners), 0) AS unique_cloners
                        FROM traffic_daily
                        WHERE repository_id = :repositoryId AND traffic_date >= :fromDate
                        """)
                .param("repositoryId", repositoryId)
                .param("fromDate", fromInclusive)
                .query((rs, rowNum) -> new TrafficTotals(
                        rs.getLong("views"),
                        rs.getLong("unique_visitors"),
                        rs.getLong("clones"),
                        rs.getLong("unique_cloners")
                ))
                .single();
    }

    public TrafficTotals portfolioTotals(LocalDate fromInclusive) {
        if (fromInclusive == null) {
            return jdbcClient.sql("""
                            SELECT COALESCE(SUM(t.views), 0) AS views,
                                   COALESCE(SUM(t.unique_visitors), 0) AS unique_visitors,
                                   COALESCE(SUM(t.clones), 0) AS clones,
                                   COALESCE(SUM(t.unique_cloners), 0) AS unique_cloners
                            FROM traffic_daily t
                            JOIN repository r ON r.id = t.repository_id
                            WHERE r.tracking_enabled = TRUE
                            """)
                    .query((rs, rowNum) -> new TrafficTotals(
                            rs.getLong("views"),
                            rs.getLong("unique_visitors"),
                            rs.getLong("clones"),
                            rs.getLong("unique_cloners")
                    ))
                    .single();
        }
        return jdbcClient.sql("""
                        SELECT COALESCE(SUM(t.views), 0) AS views,
                               COALESCE(SUM(t.unique_visitors), 0) AS unique_visitors,
                               COALESCE(SUM(t.clones), 0) AS clones,
                               COALESCE(SUM(t.unique_cloners), 0) AS unique_cloners
                        FROM traffic_daily t
                        JOIN repository r ON r.id = t.repository_id
                        WHERE r.tracking_enabled = TRUE AND t.traffic_date >= :fromDate
                        """)
                .param("fromDate", fromInclusive)
                .query((rs, rowNum) -> new TrafficTotals(
                        rs.getLong("views"),
                        rs.getLong("unique_visitors"),
                        rs.getLong("clones"),
                        rs.getLong("unique_cloners")
                ))
                .single();
    }

    public TrafficTotals portfolioTotalsInRange(LocalDate fromInclusive, LocalDate toInclusive) {
        return jdbcClient.sql("""
                        SELECT COALESCE(SUM(t.views), 0) AS views,
                               COALESCE(SUM(t.unique_visitors), 0) AS unique_visitors,
                               COALESCE(SUM(t.clones), 0) AS clones,
                               COALESCE(SUM(t.unique_cloners), 0) AS unique_cloners
                        FROM traffic_daily t
                        JOIN repository r ON r.id = t.repository_id
                        WHERE r.tracking_enabled = TRUE
                          AND t.traffic_date >= :fromDate
                          AND t.traffic_date <= :toDate
                        """)
                .param("fromDate", fromInclusive)
                .param("toDate", toInclusive)
                .query((rs, rowNum) -> new TrafficTotals(
                        rs.getLong("views"),
                        rs.getLong("unique_visitors"),
                        rs.getLong("clones"),
                        rs.getLong("unique_cloners")
                ))
                .single();
    }

    public List<RepositoryPeriodTotals> totalsByTrackedRepository(LocalDate fromInclusive, LocalDate toInclusive) {
        return jdbcClient.sql("""
                        SELECT r.id,
                               r.full_name,
                               r.stars,
                               r.archived,
                               r.last_commit_at,
                               r.github_pushed_at,
                               COALESCE(SUM(t.views), 0) AS views,
                               COALESCE(SUM(t.unique_visitors), 0) AS unique_visitors,
                               COALESCE(SUM(t.clones), 0) AS clones
                        FROM repository r
                        LEFT JOIN traffic_daily t
                          ON t.repository_id = r.id
                         AND t.traffic_date >= :fromDate
                         AND t.traffic_date <= :toDate
                        WHERE r.tracking_enabled = TRUE
                        GROUP BY r.id, r.full_name, r.stars, r.archived, r.last_commit_at, r.github_pushed_at
                        ORDER BY unique_visitors DESC, r.full_name
                        """)
                .param("fromDate", fromInclusive)
                .param("toDate", toInclusive)
                .query((rs, rowNum) -> new RepositoryPeriodTotals(
                        rs.getLong("id"),
                        rs.getString("full_name"),
                        rs.getLong("unique_visitors"),
                        rs.getLong("views"),
                        rs.getLong("clones"),
                        rs.getInt("stars"),
                        rs.getBoolean("archived"),
                        toInstant(rs.getTimestamp("last_commit_at")),
                        toInstant(rs.getTimestamp("github_pushed_at"))
                ))
                .list();
    }

    public List<DailyTotals> dailyPortfolio(LocalDate fromInclusive, LocalDate toInclusive) {
        return jdbcClient.sql("""
                        SELECT t.traffic_date,
                               SUM(t.views) AS views,
                               SUM(t.unique_visitors) AS unique_visitors,
                               SUM(t.clones) AS clones
                        FROM traffic_daily t
                        JOIN repository r ON r.id = t.repository_id
                        WHERE r.tracking_enabled = TRUE
                          AND t.traffic_date >= :fromDate
                          AND t.traffic_date <= :toDate
                        GROUP BY t.traffic_date
                        ORDER BY t.traffic_date
                        """)
                .param("fromDate", fromInclusive)
                .param("toDate", toInclusive)
                .query((rs, rowNum) -> new DailyTotals(
                        rs.getObject("traffic_date", LocalDate.class),
                        rs.getLong("views"),
                        rs.getLong("unique_visitors"),
                        rs.getLong("clones")
                ))
                .list();
    }

    public boolean hasTrackedTraffic() {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM traffic_daily t
                            JOIN repository r ON r.id = t.repository_id
                            WHERE r.tracking_enabled = TRUE
                        )
                        """)
                .query(Boolean.class)
                .single();
    }

    public Optional<LocalDate> earliestTrackedTrafficDate() {
        return jdbcClient.sql("""
                        SELECT MIN(t.traffic_date)
                        FROM traffic_daily t
                        JOIN repository r ON r.id = t.repository_id
                        WHERE r.tracking_enabled = TRUE
                        """)
                .query(LocalDate.class)
                .optional();
    }

    public Long starsChangeSince(LocalDate fromInclusive) {
        return jdbcClient.sql("""
                        WITH tracked AS (
                            SELECT id, stars FROM repository WHERE tracking_enabled = TRUE
                        ),
                        baseline AS (
                            SELECT DISTINCT ON (s.repository_id)
                                   s.repository_id,
                                   s.stars
                            FROM repository_daily_stats s
                            JOIN tracked t ON t.id = s.repository_id
                            WHERE s.stat_date < :fromDate
                            ORDER BY s.repository_id, s.stat_date DESC
                        )
                        SELECT SUM(t.stars - b.stars)
                        FROM tracked t
                        JOIN baseline b ON b.repository_id = t.id
                        """)
                .param("fromDate", fromInclusive)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    public List<Observation> referrerSnapshotsForDelta(
            long repositoryId,
            LocalDate fromInclusive,
            LocalDate toInclusive
    ) {
        return jdbcClient.sql("""
                        WITH predecessor AS (
                            SELECT MAX(snapshot_date) AS snapshot_date
                            FROM traffic_referrer_snapshot
                            WHERE repository_id = :repositoryId
                              AND snapshot_date < :fromDate
                        )
                        SELECT s.snapshot_date, s.referrer, s.views, s.unique_visitors
                        FROM traffic_referrer_snapshot s
                        WHERE s.repository_id = :repositoryId
                          AND (
                              (s.snapshot_date >= :fromDate AND s.snapshot_date <= :toDate)
                              OR s.snapshot_date = (SELECT snapshot_date FROM predecessor)
                          )
                        ORDER BY s.snapshot_date, s.referrer
                        """)
                .param("repositoryId", repositoryId)
                .param("fromDate", fromInclusive)
                .param("toDate", toInclusive)
                .query((rs, rowNum) -> new Observation(
                        rs.getObject("snapshot_date", LocalDate.class),
                        rs.getString("referrer"),
                        null,
                        rs.getInt("views"),
                        rs.getInt("unique_visitors")
                ))
                .list();
    }

    public List<Observation> pathSnapshotsForDelta(
            long repositoryId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            boolean includeService
    ) {
        return jdbcClient.sql("""
                        WITH visible AS (
                            SELECT snapshot_date, path, title, views, unique_visitors
                            FROM traffic_path_snapshot
                            WHERE repository_id = :repositoryId
                              AND (:includeService OR service_path = FALSE)
                        ),
                        predecessor AS (
                            SELECT MAX(snapshot_date) AS snapshot_date
                            FROM visible
                            WHERE snapshot_date < :fromDate
                        )
                        SELECT v.snapshot_date, v.path, v.title, v.views, v.unique_visitors
                        FROM visible v
                        WHERE (v.snapshot_date >= :fromDate AND v.snapshot_date <= :toDate)
                           OR v.snapshot_date = (SELECT snapshot_date FROM predecessor)
                        ORDER BY v.snapshot_date, v.path
                        """)
                .param("repositoryId", repositoryId)
                .param("fromDate", fromInclusive)
                .param("toDate", toInclusive)
                .param("includeService", includeService)
                .query((rs, rowNum) -> new Observation(
                        rs.getObject("snapshot_date", LocalDate.class),
                        rs.getString("path"),
                        rs.getString("title"),
                        rs.getInt("views"),
                        rs.getInt("unique_visitors")
                ))
                .list();
    }

    public Optional<LocalDate> earliestReferrerSnapshotDate(long repositoryId) {
        return jdbcClient.sql("""
                        SELECT MIN(snapshot_date)
                        FROM traffic_referrer_snapshot
                        WHERE repository_id = :repositoryId
                        """)
                .param("repositoryId", repositoryId)
                .query(LocalDate.class)
                .optional()
                .filter(date -> date != null);
    }

    /**
     * The cards mirror the GitHub traffic page, so they show the newest stored snapshot as is
     * instead of anything aggregated over the selected period.
     */
    public ReferrerSnapshot latestReferrerSnapshot(long repositoryId) {
        List<TimedReferrer> rows = jdbcClient.sql("""
                        WITH latest AS (
                            SELECT MAX(snapshot_date) AS snapshot_date
                            FROM traffic_referrer_snapshot
                            WHERE repository_id = :repositoryId
                        )
                        SELECT s.snapshot_at, s.referrer, s.views, s.unique_visitors
                        FROM traffic_referrer_snapshot s
                        JOIN latest l ON l.snapshot_date = s.snapshot_date
                        WHERE s.repository_id = :repositoryId
                        ORDER BY s.unique_visitors DESC, s.views DESC, s.referrer
                        """)
                .param("repositoryId", repositoryId)
                .query((rs, rowNum) -> new TimedReferrer(
                        toInstant(rs.getTimestamp("snapshot_at")),
                        new ReferrerRow(
                                rs.getString("referrer"),
                                rs.getInt("views"),
                                rs.getInt("unique_visitors")
                        )
                ))
                .list();
        return new ReferrerSnapshot(
                rows.isEmpty() ? null : rows.getFirst().snapshotAt(),
                rows.stream().map(TimedReferrer::row).toList()
        );
    }

    public PathSnapshot latestPathSnapshot(long repositoryId, boolean includeService) {
        List<TimedPath> rows = jdbcClient.sql("""
                        WITH visible AS (
                            SELECT snapshot_date, snapshot_at, path, title, views, unique_visitors, service_path
                            FROM traffic_path_snapshot
                            WHERE repository_id = :repositoryId
                              AND (:includeService OR service_path = FALSE)
                        ),
                        latest AS (
                            SELECT MAX(snapshot_date) AS snapshot_date
                            FROM visible
                        )
                        SELECT v.snapshot_at, v.path, v.title, v.views, v.unique_visitors, v.service_path
                        FROM visible v
                        JOIN latest l ON l.snapshot_date = v.snapshot_date
                        ORDER BY v.unique_visitors DESC, v.views DESC, v.path
                        """)
                .param("repositoryId", repositoryId)
                .param("includeService", includeService)
                .query((rs, rowNum) -> new TimedPath(
                        toInstant(rs.getTimestamp("snapshot_at")),
                        new PathRow(
                                rs.getString("path"),
                                rs.getString("title"),
                                rs.getInt("views"),
                                rs.getInt("unique_visitors"),
                                rs.getBoolean("service_path")
                        )
                ))
                .list();
        return new PathSnapshot(
                rows.isEmpty() ? null : rows.getFirst().snapshotAt(),
                rows.stream().map(TimedPath::row).toList()
        );
    }

    public record TrafficTotals(long views, long uniqueVisitors, long clones, long uniqueCloners) {
    }

    public record RepositoryPeriodTotals(
            long repositoryId,
            String fullName,
            long uniqueVisitors,
            long views,
            long clones,
            int stars,
            boolean archived,
            Instant lastCommitAt,
            Instant githubPushedAt
    ) {
    }

    public record RepositoryDailyStats(
            LocalDate statDate,
            int stars,
            int watchers,
            int forks,
            int contributors
    ) {
    }

    public record DailyTotals(LocalDate date, long views, long uniqueVisitors, long clones) {
    }

    public record ReferrerRow(String referrer, int views, int uniqueVisitors) {
    }

    public record PathRow(String path, String title, int views, int uniqueVisitors, boolean servicePath) {
    }

    public record ReferrerSnapshot(Instant snapshotAt, List<ReferrerRow> rows) {
    }

    public record PathSnapshot(Instant snapshotAt, List<PathRow> rows) {
    }

    private record TimedReferrer(Instant snapshotAt, ReferrerRow row) {
    }

    private record TimedPath(Instant snapshotAt, PathRow row) {
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
