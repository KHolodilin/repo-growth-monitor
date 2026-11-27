package com.kholodilin.repogrowth.stats.repository;

import com.kholodilin.repogrowth.stats.model.RepositoryDailyStats;
import com.kholodilin.repogrowth.stats.model.RepositoryStatDay;
import java.sql.Array;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence for {@code repository_daily_stats} and contributor login tips. */
@Repository
public class StatsJdbcRepository {

  private static final RowMapper<RepositoryDailyStats> DAILY_STATS_MAPPER =
      (rs, rowNum) ->
          new RepositoryDailyStats(
              rs.getObject("stat_date", LocalDate.class),
              rs.getInt("stars"),
              rs.getInt("watchers"),
              rs.getInt("forks"),
              rs.getInt("contributors"));

  private final JdbcClient jdbcClient;

  public StatsJdbcRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public void upsertDailyStats(
      long repositoryId,
      LocalDate date,
      int stars,
      int watchers,
      int forks,
      int openIssues,
      int contributors) {
    jdbcClient
        .sql(
            """
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

  public void replaceContributorLogins(long repositoryId, LocalDate date, List<String> logins) {
    jdbcClient
        .sql(
            """
            DELETE FROM repository_daily_contributor_login
            WHERE repository_id = :repositoryId AND stat_date = :statDate
            """)
        .param("repositoryId", repositoryId)
        .param("statDate", date)
        .update();
    for (String login : logins) {
      jdbcClient
          .sql(
              """
              INSERT INTO repository_daily_contributor_login (repository_id, stat_date, login)
              VALUES (:repositoryId, :statDate, :login)
              """)
          .param("repositoryId", repositoryId)
          .param("statDate", date)
          .param("login", login)
          .update();
    }
    jdbcClient
        .sql(
            """
            UPDATE repository_daily_stats
            SET contributor_logins_collected = TRUE
            WHERE repository_id = :repositoryId AND stat_date = :statDate
            """)
        .param("repositoryId", repositoryId)
        .param("statDate", date)
        .update();
  }

  public List<RepositoryDailyStats> dailyStatsHistory(long repositoryId, LocalDate fromInclusive) {
    if (fromInclusive == null) {
      return jdbcClient
          .sql(
              """
              SELECT stat_date, stars, watchers, forks, contributors
              FROM repository_daily_stats
              WHERE repository_id = :repositoryId
              ORDER BY stat_date
              """)
          .param("repositoryId", repositoryId)
          .query(DAILY_STATS_MAPPER)
          .list();
    }
    return jdbcClient
        .sql(
            """
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

  public Optional<RepositoryDailyStats> latestDailyStats(long repositoryId) {
    return latestDailyStatsOnOrBefore(repositoryId, null);
  }

  /** Tip snapshot at period end (inclusive). Null asOf keeps absolute latest. */
  public Optional<RepositoryDailyStats> latestDailyStatsOnOrBefore(
      long repositoryId, LocalDate asOf) {
    if (asOf == null) {
      return jdbcClient
          .sql(
              """
              SELECT stat_date, stars, watchers, forks, contributors
              FROM repository_daily_stats
              WHERE repository_id = :repositoryId
              ORDER BY stat_date DESC
              LIMIT 1
              """)
          .param("repositoryId", repositoryId)
          .query(DAILY_STATS_MAPPER)
          .optional();
    }
    return jdbcClient
        .sql(
            """
            SELECT stat_date, stars, watchers, forks, contributors
            FROM repository_daily_stats
            WHERE repository_id = :repositoryId AND stat_date <= :asOf
            ORDER BY stat_date DESC
            LIMIT 1
            """)
        .param("repositoryId", repositoryId)
        .param("asOf", asOf)
        .query(DAILY_STATS_MAPPER)
        .optional();
  }

  public List<RepositoryStatDay> trackedDailyStats(LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbcClient
        .sql(
            """
            SELECT s.repository_id,
                   r.full_name,
                   s.stat_date,
                   s.stars,
                   s.watchers,
                   s.forks,
                   s.contributors,
                   s.contributor_logins_collected,
                   (
                       SELECT COALESCE(array_agg(c.login ORDER BY c.login), ARRAY[]::text[])
                       FROM repository_daily_contributor_login c
                       WHERE c.repository_id = s.repository_id AND c.stat_date = s.stat_date
                   ) AS contributor_logins
            FROM repository_daily_stats s
            JOIN repository r ON r.id = s.repository_id
            WHERE r.tracking_enabled = TRUE
              AND s.stat_date >= :fromDate
              AND s.stat_date <= :toDate
            ORDER BY s.stat_date, r.full_name
            """)
        .param("fromDate", fromInclusive)
        .param("toDate", toInclusive)
        .query((rs, rowNum) ->
            new RepositoryStatDay(
                rs.getLong("repository_id"),
                rs.getString("full_name"),
                rs.getObject("stat_date", LocalDate.class),
                rs.getInt("stars"),
                rs.getInt("forks"),
                rs.getInt("watchers"),
                rs.getInt("contributors"),
                readLogins(
                    rs.getBoolean("contributor_logins_collected"),
                    rs.getArray("contributor_logins"))))
        .list();
  }

  public List<RepositoryStatDay> latestTrackedDailyStats() {
    return latestTrackedDailyStatsOnOrBefore(null);
  }

  /** Per-repo tip on or before asOf (period end). Null asOf = absolute latest. */
  public List<RepositoryStatDay> latestTrackedDailyStatsOnOrBefore(LocalDate asOf) {
    return jdbcClient
        .sql(
            """
            SELECT DISTINCT ON (s.repository_id)
                   s.repository_id,
                   r.full_name,
                   s.stat_date,
                   s.stars,
                   s.watchers,
                   s.forks,
                   s.contributors,
                   s.contributor_logins_collected,
                   (
                       SELECT COALESCE(array_agg(c.login ORDER BY c.login), ARRAY[]::text[])
                       FROM repository_daily_contributor_login c
                       WHERE c.repository_id = s.repository_id AND c.stat_date = s.stat_date
                   ) AS contributor_logins
            FROM repository_daily_stats s
            JOIN repository r ON r.id = s.repository_id
            WHERE r.tracking_enabled = TRUE
              AND (:asOf::date IS NULL OR s.stat_date <= :asOf)
            ORDER BY s.repository_id, s.stat_date DESC
            """)
        .param("asOf", asOf)
        .query((rs, rowNum) ->
            new RepositoryStatDay(
                rs.getLong("repository_id"),
                rs.getString("full_name"),
                rs.getObject("stat_date", LocalDate.class),
                rs.getInt("stars"),
                rs.getInt("forks"),
                rs.getInt("watchers"),
                rs.getInt("contributors"),
                readLogins(
                    rs.getBoolean("contributor_logins_collected"),
                    rs.getArray("contributor_logins"))))
        .list();
  }

  static List<String> readLogins(boolean collected, Array array) {
    if (!collected) {
      return null;
    }
    if (array == null) {
      return List.of();
    }
    try {
      Object raw = array.getArray();
      if (!(raw instanceof Object[] values)) {
        return List.of();
      }
      List<String> logins = new ArrayList<>(values.length);
      for (Object value : values) {
        if (value != null) {
          logins.add(String.valueOf(value));
        }
      }
      return logins;
    } catch (Exception ex) {
      return List.of();
    }
  }

  public Optional<LocalDate> earliestDailyStatsDate(long repositoryId) {
    return jdbcClient
        .sql(
            """
            SELECT MIN(stat_date)
            FROM repository_daily_stats
            WHERE repository_id = :repositoryId
            """)
        .param("repositoryId", repositoryId)
        .query(LocalDate.class)
        .optional()
        .filter(date -> date != null);
  }

  public Long starsChangeSince(LocalDate fromInclusive) {
    return jdbcClient
        .sql(
            """
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
}
