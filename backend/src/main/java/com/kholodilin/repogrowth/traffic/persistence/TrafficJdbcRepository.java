package com.kholodilin.repogrowth.traffic.persistence;

import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.traffic.SnapshotPeriodMath;
import com.kholodilin.repogrowth.traffic.SnapshotPeriodMath.DayTraffic;
import com.kholodilin.repogrowth.traffic.SnapshotPeriodMath.Observation;
import com.kholodilin.repogrowth.traffic.domain.TrafficDaily;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class TrafficJdbcRepository {

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

    public void upsertDailyStats(long repositoryId, LocalDate date, int stars, int watchers, int forks, int openIssues) {
        jdbcClient.sql("""
                        INSERT INTO repository_daily_stats (repository_id, stat_date, stars, watchers, forks, open_issues)
                        VALUES (:repositoryId, :statDate, :stars, :watchers, :forks, :openIssues)
                        ON CONFLICT (repository_id, stat_date) DO UPDATE SET
                            stars = EXCLUDED.stars,
                            watchers = EXCLUDED.watchers,
                            forks = EXCLUDED.forks,
                            open_issues = EXCLUDED.open_issues
                        """)
                .param("repositoryId", repositoryId)
                .param("statDate", date)
                .param("stars", stars)
                .param("watchers", watchers)
                .param("forks", forks)
                .param("openIssues", openIssues)
                .update();
    }

    public void insertReferrers(long repositoryId, Instant snapshotAt, String referrer, int views, int uniqueVisitors) {
        jdbcClient.sql("""
                        INSERT INTO traffic_referrer_snapshot (repository_id, snapshot_at, referrer, views, unique_visitors)
                        VALUES (:repositoryId, :snapshotAt, :referrer, :views, :uniqueVisitors)
                        """)
                .param("repositoryId", repositoryId)
                .param("snapshotAt", SqlTime.ts(snapshotAt))
                .param("referrer", referrer)
                .param("views", views)
                .param("uniqueVisitors", uniqueVisitors)
                .update();
    }

    public void insertPath(
            long repositoryId,
            Instant snapshotAt,
            String path,
            String title,
            int views,
            int uniqueVisitors
    ) {
        jdbcClient.sql("""
                        INSERT INTO traffic_path_snapshot (repository_id, snapshot_at, path, title, views, unique_visitors)
                        VALUES (:repositoryId, :snapshotAt, :path, :title, :views, :uniqueVisitors)
                        """)
                .param("repositoryId", repositoryId)
                .param("snapshotAt", SqlTime.ts(snapshotAt))
                .param("path", path)
                .param("title", title)
                .param("views", views)
                .param("uniqueVisitors", uniqueVisitors)
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
            LocalDate toInclusive,
            ZoneId zone
    ) {
        String tz = postgresTimeZone(zone);
        return jdbcClient.sql("""
                        WITH dated AS (
                            SELECT
                                (snapshot_at AT TIME ZONE :tz)::date AS snapshot_date,
                                snapshot_at,
                                referrer,
                                views,
                                unique_visitors
                            FROM traffic_referrer_snapshot
                            WHERE repository_id = :repositoryId
                        ),
                        latest AS (
                            SELECT snapshot_date, MAX(snapshot_at) AS snapshot_at
                            FROM dated
                            GROUP BY snapshot_date
                        ),
                        predecessor AS (
                            SELECT MAX(snapshot_date) AS snapshot_date
                            FROM latest
                            WHERE snapshot_date < :fromDate
                        ),
                        wanted AS (
                            SELECT snapshot_date
                            FROM latest
                            WHERE snapshot_date >= :fromDate
                              AND snapshot_date <= :toDate
                            UNION
                            SELECT snapshot_date
                            FROM predecessor
                            WHERE snapshot_date IS NOT NULL
                        )
                        SELECT d.snapshot_date, d.referrer, d.views, d.unique_visitors
                        FROM dated d
                        JOIN latest l
                          ON l.snapshot_date = d.snapshot_date
                         AND l.snapshot_at = d.snapshot_at
                        JOIN wanted w ON w.snapshot_date = d.snapshot_date
                        ORDER BY d.snapshot_date, d.referrer
                        """)
                .param("repositoryId", repositoryId)
                .param("tz", tz)
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

    public Optional<LocalDate> earliestReferrerSnapshotDate(long repositoryId, ZoneId zone) {
        return jdbcClient.sql("""
                        SELECT MIN((snapshot_at AT TIME ZONE :tz)::date)
                        FROM traffic_referrer_snapshot
                        WHERE repository_id = :repositoryId
                        """)
                .param("repositoryId", repositoryId)
                .param("tz", postgresTimeZone(zone))
                .query(LocalDate.class)
                .optional()
                .filter(date -> date != null);
    }

    public List<ReferrerRow> referrersInRange(long repositoryId, LocalDate fromInclusive, LocalDate toInclusive, ZoneId zone) {
        return SnapshotPeriodMath.aggregate(
                fromInclusive,
                toInclusive,
                referrerObservations(repositoryId, fromInclusive, toInclusive, zone),
                trafficWeights(repositoryId, fromInclusive, toInclusive)
        ).stream()
                .map(row -> new ReferrerRow(row.key(), row.views(), row.uniqueVisitors()))
                .toList();
    }

    public List<PathRow> pathsInRange(long repositoryId, LocalDate fromInclusive, LocalDate toInclusive, ZoneId zone) {
        return SnapshotPeriodMath.aggregate(
                fromInclusive,
                toInclusive,
                pathObservations(repositoryId, fromInclusive, toInclusive, zone),
                trafficWeights(repositoryId, fromInclusive, toInclusive)
        ).stream()
                .map(row -> new PathRow(row.key(), row.title(), row.views(), row.uniqueVisitors()))
                .toList();
    }

    private List<DayTraffic> trafficWeights(long repositoryId, LocalDate fromInclusive, LocalDate toInclusive) {
        LocalDate lookback = fromInclusive.minusDays(SnapshotPeriodMath.GITHUB_WINDOW_DAYS - 1);
        return history(repositoryId, lookback).stream()
                .filter(day -> !day.trafficDate().isAfter(toInclusive))
                .map(day -> new DayTraffic(day.trafficDate(), day.views(), day.uniqueVisitors()))
                .toList();
    }

    private List<Observation> referrerObservations(
            long repositoryId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            ZoneId zone
    ) {
        String tz = postgresTimeZone(zone);
        return jdbcClient.sql("""
                        WITH dated AS (
                            SELECT
                                (snapshot_at AT TIME ZONE :tz)::date AS snapshot_date,
                                snapshot_at,
                                referrer,
                                views,
                                unique_visitors
                            FROM traffic_referrer_snapshot
                            WHERE repository_id = :repositoryId
                        ),
                        latest AS (
                            SELECT snapshot_date, MAX(snapshot_at) AS snapshot_at
                            FROM dated
                            WHERE snapshot_date >= :fromDate
                              AND snapshot_date <= :toDate
                            GROUP BY snapshot_date
                        )
                        SELECT d.snapshot_date, d.referrer, d.views, d.unique_visitors
                        FROM dated d
                        JOIN latest l
                          ON l.snapshot_date = d.snapshot_date
                         AND l.snapshot_at = d.snapshot_at
                        """)
                .param("repositoryId", repositoryId)
                .param("tz", tz)
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

    private List<Observation> pathObservations(
            long repositoryId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            ZoneId zone
    ) {
        String tz = postgresTimeZone(zone);
        return jdbcClient.sql("""
                        WITH dated AS (
                            SELECT
                                (snapshot_at AT TIME ZONE :tz)::date AS snapshot_date,
                                snapshot_at,
                                path,
                                title,
                                views,
                                unique_visitors
                            FROM traffic_path_snapshot
                            WHERE repository_id = :repositoryId
                        ),
                        latest AS (
                            SELECT snapshot_date, MAX(snapshot_at) AS snapshot_at
                            FROM dated
                            WHERE snapshot_date >= :fromDate
                              AND snapshot_date <= :toDate
                            GROUP BY snapshot_date
                        )
                        SELECT d.snapshot_date, d.path, d.title, d.views, d.unique_visitors
                        FROM dated d
                        JOIN latest l
                          ON l.snapshot_date = d.snapshot_date
                         AND l.snapshot_at = d.snapshot_at
                        """)
                .param("repositoryId", repositoryId)
                .param("tz", tz)
                .param("fromDate", fromInclusive)
                .param("toDate", toInclusive)
                .query((rs, rowNum) -> new Observation(
                        rs.getObject("snapshot_date", LocalDate.class),
                        rs.getString("path"),
                        rs.getString("title"),
                        rs.getInt("views"),
                        rs.getInt("unique_visitors")
                ))
                .list();
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

    public record DailyTotals(LocalDate date, long views, long uniqueVisitors, long clones) {
    }

    public record ReferrerRow(String referrer, int views, int uniqueVisitors) {
    }

    public record PathRow(String path, String title, int views, int uniqueVisitors) {
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String postgresTimeZone(ZoneId zone) {
        if (zone == null || ZoneOffset.UTC.equals(zone.normalized())) {
            return "UTC";
        }
        String id = zone.getId();
        return "Z".equals(id) ? "UTC" : id;
    }
}
