package com.kholodilin.repogrowth.common.config;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the deterministic {@code northstar} owner fixture into Postgres for {@code
 * docker-compose.mock.yml}. WireMock already serves GitHub; without orchestration + collector the
 * product DB stays empty, so this runner materializes owner/repos/~30d metrics for public dashboard
 * / chart regression. Uses JdbcClient only (no module coupling).
 */
@Component
@ConditionalOnProperty(name = "rgm.mock.seed-northstar", havingValue = "true")
public class NorthstarMockSeed implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(NorthstarMockSeed.class);

  public static final String OWNER_LOGIN = "northstar";
  public static final long OWNER_GITHUB_ID = 90_042L;
  public static final int TRAFFIC_DAYS = 30;

  private static final List<RepoSpec> REPOS =
      List.of(
          new RepoSpec(91_001L, "pulse-board", false, "Java", 240, 24, 20, 1),
          new RepoSpec(91_002L, "signal-router", false, "Java", 180, 18, 15, 2),
          new RepoSpec(91_003L, "lake-ingest", false, "Python", 95, 9, 7, 3),
          new RepoSpec(91_004L, "edge-cache", false, "Go", 310, 31, 28, 4),
          new RepoSpec(91_005L, "trust-vault", true, "Java", 150, 15, 12, 2),
          new RepoSpec(91_006L, "schema-forge", false, "TypeScript", 88, 8, 6, 1),
          new RepoSpec(91_007L, "fleet-agent", false, "Go", 122, 12, 10, 2),
          new RepoSpec(91_008L, "chart-kit", false, "TypeScript", 64, 6, 5, 1),
          new RepoSpec(91_009L, "rank-engine", false, "Java", 205, 20, 17, 3),
          new RepoSpec(91_010L, "audit-ledger", false, "Java", 73, 7, 5, 1));

  private final JdbcClient jdbc;
  private final Clock clock;

  public NorthstarMockSeed(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    long ownerId = upsertOwner();
    Instant now = clock.instant();
    LocalDate today = LocalDate.now(clock);
    int ordinal = 0;
    for (RepoSpec spec : REPOS) {
      long repositoryId = upsertRepository(ownerId, spec, now);
      publishAndTrack(repositoryId, !spec.privateRepo(), now);
      seedMetrics(repositoryId, ordinal, today, spec);
      ordinal++;
    }
    log.info(
        "Seeded northstar mock fixture: owner={} repos={} trafficDays={}",
        OWNER_LOGIN,
        REPOS.size(),
        TRAFFIC_DAYS);
  }

  private long upsertOwner() {
    return jdbc.sql(
            """
            INSERT INTO github_owner (github_id, login, owner_type, avatar_url, html_url)
            VALUES (:githubId, :login, 'USER', :avatarUrl, :htmlUrl)
            ON CONFLICT (github_id) DO UPDATE SET
              login = EXCLUDED.login,
              avatar_url = EXCLUDED.avatar_url,
              html_url = EXCLUDED.html_url,
              updated_at = NOW()
            RETURNING id
            """)
        .param("githubId", OWNER_GITHUB_ID)
        .param("login", OWNER_LOGIN)
        .param("avatarUrl", "https://avatars.githubusercontent.com/u/90042?v=4")
        .param("htmlUrl", "https://github.com/northstar")
        .query(Long.class)
        .single();
  }

  private long upsertRepository(long ownerId, RepoSpec spec, Instant now) {
    return jdbc.sql(
            """
            INSERT INTO repository (
              github_id, owner_id, name, full_name, description, visibility, default_branch,
              language, fork, archived, stars, watchers, forks, open_issues, contributors,
              tracking_enabled, account_accessible, github_created_at, github_updated_at,
              github_pushed_at, last_commit_at, enriched_at
            ) VALUES (
              :githubId, :ownerId, :name, :fullName, :description, :visibility, 'main',
              :language, FALSE, FALSE, :stars, :watchers, :forks, :openIssues, :contributors,
              FALSE, TRUE, :createdAt, :updatedAt, :pushedAt, :pushedAt, :enrichedAt
            )
            ON CONFLICT (github_id) DO UPDATE SET
              owner_id = EXCLUDED.owner_id,
              name = EXCLUDED.name,
              full_name = EXCLUDED.full_name,
              description = EXCLUDED.description,
              visibility = EXCLUDED.visibility,
              language = EXCLUDED.language,
              stars = EXCLUDED.stars,
              watchers = EXCLUDED.watchers,
              forks = EXCLUDED.forks,
              open_issues = EXCLUDED.open_issues,
              contributors = EXCLUDED.contributors,
              account_accessible = TRUE,
              github_updated_at = EXCLUDED.github_updated_at,
              github_pushed_at = EXCLUDED.github_pushed_at,
              last_commit_at = EXCLUDED.last_commit_at,
              enriched_at = EXCLUDED.enriched_at,
              updated_at = NOW()
            RETURNING id
            """)
        .param("githubId", spec.githubId())
        .param("ownerId", ownerId)
        .param("name", spec.name())
        .param("fullName", OWNER_LOGIN + "/" + spec.name())
        .param(
            "description",
            "Deterministic mock repository " + spec.name() + " for RGM UI regression")
        .param("visibility", spec.privateRepo() ? "PRIVATE" : "PUBLIC")
        .param("language", spec.language())
        .param("stars", spec.stars())
        .param("watchers", spec.watchers())
        .param("forks", spec.forks())
        .param("openIssues", spec.openIssues())
        .param("contributors", Math.max(1, spec.watchers() / 3))
        .param("createdAt", Timestamp.from(Instant.parse("2024-01-15T10:00:00Z")))
        .param("updatedAt", Timestamp.from(Instant.parse("2026-11-01T12:00:00Z")))
        .param("pushedAt", Timestamp.from(Instant.parse("2026-11-01T08:00:00Z")))
        .param("enrichedAt", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  private void publishAndTrack(long repositoryId, boolean publishPublic, Instant verifiedAt) {
    jdbc.sql(
            """
            UPDATE repository SET
              tracking_enabled = TRUE,
              account_accessible = TRUE,
              public_dashboard_enabled = :publish,
              public_traffic_enabled = :publish,
              public_referrers_enabled = :publish,
              public_paths_enabled = :publish,
              public_topics_enabled = :publish,
              visibility_verified_at = :verifiedAt,
              updated_at = NOW()
            WHERE id = :id
            """)
        .param("publish", publishPublic)
        .param("verifiedAt", Timestamp.from(verifiedAt))
        .param("id", repositoryId)
        .update();
  }

  private void seedMetrics(long repositoryId, int ordinal, LocalDate today, RepoSpec spec) {
    for (int dayIndex = 0; dayIndex < TRAFFIC_DAYS; dayIndex++) {
      LocalDate date = today.minusDays(TRAFFIC_DAYS - 1L - dayIndex);
      int views = viewsFor(ordinal, dayIndex);
      int visitors = Math.max(1, (views * 2) / 5);
      int clones = clonesFor(ordinal, dayIndex);
      int cloners = Math.max(1, (clones * 2) / 3);
      jdbc.sql(
              """
              INSERT INTO traffic_daily (
                repository_id, traffic_date, views, unique_visitors, clones, unique_cloners
              ) VALUES (
                :repositoryId, :trafficDate, :views, :visitors, :clones, :cloners
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
          .param("visitors", visitors)
          .param("clones", clones)
          .param("cloners", cloners)
          .update();
      int stars = Math.max(1, spec.stars() - (TRAFFIC_DAYS - 1 - dayIndex));
      int watchers = Math.max(1, spec.watchers() - (TRAFFIC_DAYS - 1 - dayIndex) / 5);
      int forks = Math.max(0, spec.forks() - (TRAFFIC_DAYS - 1 - dayIndex) / 7);
      jdbc.sql(
              """
              INSERT INTO repository_daily_stats (
                repository_id, stat_date, stars, watchers, forks, open_issues, contributors
              ) VALUES (
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
          .param("openIssues", spec.openIssues())
          .param("contributors", Math.max(1, spec.watchers() / 3))
          .update();
    }
  }

  /** Matches WireMock northstar view scale: base grows by repo ordinal and day. */
  static int viewsFor(int repoOrdinal, int dayIndex) {
    return 40 + repoOrdinal * 19 + dayIndex * 3;
  }

  static int clonesFor(int repoOrdinal, int dayIndex) {
    return 2 + repoOrdinal + (dayIndex % 5);
  }

  private record RepoSpec(
      long githubId,
      String name,
      boolean privateRepo,
      String language,
      int stars,
      int watchers,
      int forks,
      int openIssues) {}
}
