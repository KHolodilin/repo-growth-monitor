package com.kholodilin.repogrowth.traffic.persistence;

import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.traffic.domain.TrafficDaily;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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

    public void upsertDailyStats(long repositoryId, LocalDate date, int stars, int forks, int openIssues) {
        jdbcClient.sql("""
                        INSERT INTO repository_daily_stats (repository_id, stat_date, stars, forks, open_issues)
                        VALUES (:repositoryId, :statDate, :stars, :forks, :openIssues)
                        ON CONFLICT (repository_id, stat_date) DO UPDATE SET
                            stars = EXCLUDED.stars,
                            forks = EXCLUDED.forks,
                            open_issues = EXCLUDED.open_issues
                        """)
                .param("repositoryId", repositoryId)
                .param("statDate", date)
                .param("stars", stars)
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

    public Instant latestReferrerSnapshot(long repositoryId) {
        return jdbcClient.sql("""
                        SELECT MAX(snapshot_at) FROM traffic_referrer_snapshot WHERE repository_id = :repositoryId
                        """)
                .param("repositoryId", repositoryId)
                .query(Instant.class)
                .optional()
                .orElse(null);
    }

    public Instant latestPathSnapshot(long repositoryId) {
        return jdbcClient.sql("""
                        SELECT MAX(snapshot_at) FROM traffic_path_snapshot WHERE repository_id = :repositoryId
                        """)
                .param("repositoryId", repositoryId)
                .query(Instant.class)
                .optional()
                .orElse(null);
    }

    public List<ReferrerRow> referrers(long repositoryId, Instant snapshotAt) {
        if (snapshotAt == null) {
            return List.of();
        }
        return jdbcClient.sql("""
                        SELECT referrer, views, unique_visitors
                        FROM traffic_referrer_snapshot
                        WHERE repository_id = :repositoryId AND snapshot_at = :snapshotAt
                        ORDER BY views DESC
                        """)
                .param("repositoryId", repositoryId)
                .param("snapshotAt", SqlTime.ts(snapshotAt))
                .query((rs, rowNum) -> new ReferrerRow(
                        rs.getString("referrer"),
                        rs.getInt("views"),
                        rs.getInt("unique_visitors")
                ))
                .list();
    }

    public List<PathRow> paths(long repositoryId, Instant snapshotAt) {
        if (snapshotAt == null) {
            return List.of();
        }
        return jdbcClient.sql("""
                        SELECT path, title, views, unique_visitors
                        FROM traffic_path_snapshot
                        WHERE repository_id = :repositoryId AND snapshot_at = :snapshotAt
                        ORDER BY views DESC
                        """)
                .param("repositoryId", repositoryId)
                .param("snapshotAt", SqlTime.ts(snapshotAt))
                .query((rs, rowNum) -> new PathRow(
                        rs.getString("path"),
                        rs.getString("title"),
                        rs.getInt("views"),
                        rs.getInt("unique_visitors")
                ))
                .list();
    }

    public record TrafficTotals(long views, long uniqueVisitors, long clones, long uniqueCloners) {
    }

    public record ReferrerRow(String referrer, int views, int uniqueVisitors) {
    }

    public record PathRow(String path, String title, int views, int uniqueVisitors) {
    }
}
