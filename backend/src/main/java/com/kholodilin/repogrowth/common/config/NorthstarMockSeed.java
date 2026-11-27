package com.kholodilin.repogrowth.common.config;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
 * product DB stays empty, so this runner materializes owner/repos/~30d metrics, referrers/paths,
 * search/topic rank history and growth events for public dashboard / chart regression. Uses
 * JdbcClient only (no module coupling).
 */
@Component
@ConditionalOnProperty(name = "rgm.mock.seed-northstar", havingValue = "true")
public class NorthstarMockSeed implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(NorthstarMockSeed.class);

  public static final String OWNER_LOGIN = "northstar";
  public static final long OWNER_GITHUB_ID = 90_042L;
  public static final int TRAFFIC_DAYS = 30;
  /** Extra published owners for public homepage directory pagination tests. */
  public static final int DIRECTORY_OWNER_COUNT = 30;
  public static final long DIRECTORY_OWNER_GITHUB_ID_BASE = 92_001L;
  public static final long DIRECTORY_REPO_GITHUB_ID_BASE = 93_001L;

  private static final List<String> REFERRERS =
      List.of("github.com", "google.com", "chatgpt.com", "reddit.com", "Other");

  private static final List<String> DIRECTORY_LANGUAGES =
      List.of("Java", "Go", "Python", "TypeScript", "Rust");

  private static final List<List<String>> DIRECTORY_TOPIC_BUNDLES =
      List.of(
          List.of("observability", "metrics", "ops"),
          List.of("data", "etl", "pipeline"),
          List.of("cache", "cdn", "edge"),
          List.of("security", "secrets", "iam"),
          List.of("charts", "kpi", "ui"),
          List.of("search", "ranking", "index"),
          List.of("agent", "fleet", "runtime"),
          List.of("schema", "codegen", "api"),
          List.of("audit", "ledger", "compliance"),
          List.of("messaging", "events", "queue"));

  private static final List<RepoSpec> REPOS =
      List.of(
          northstarRepo(
              91_001L, "pulse-board", false, "Java", 240, 24, 20, 1, "observability", "dashboard", "metrics"),
          northstarRepo(
              91_002L, "signal-router", false, "Java", 180, 18, 15, 2, "kafka", "routing", "events"),
          northstarRepo(
              91_003L, "lake-ingest", false, "Python", 95, 9, 7, 3, "etl", "data", "batch"),
          northstarRepo(
              91_004L, "edge-cache", false, "Go", 310, 31, 28, 4, "cache", "cdn", "performance"),
          northstarRepo(
              91_005L, "trust-vault", true, "Java", 150, 15, 12, 2, "security", "secrets", "vault"),
          northstarRepo(
              91_006L, "schema-forge", false, "TypeScript", 88, 8, 6, 1, "schema", "codegen", "api"),
          northstarRepo(
              91_007L, "fleet-agent", false, "Go", 122, 12, 10, 2, "agent", "ops", "fleet"),
          northstarRepo(
              91_008L, "chart-kit", false, "TypeScript", 64, 6, 5, 1, "charts", "kpi", "ui"),
          northstarRepo(
              91_009L, "rank-engine", false, "Java", 205, 20, 17, 3, "search", "ranking", "topics"),
          northstarRepo(
              91_010L, "audit-ledger", false, "Java", 73, 7, 5, 1, "audit", "ledger", "compliance"));

  private final JdbcClient jdbc;
  private final Clock clock;

  public NorthstarMockSeed(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    long ownerId = upsertOwner(OWNER_GITHUB_ID, OWNER_LOGIN);
    Instant now = clock.instant();
    LocalDate today = LocalDate.now(clock);
    int ordinal = 0;
    for (RepoSpec spec : REPOS) {
      seedRichRepository(ownerId, spec, ordinal, today, now);
      ordinal++;
    }
    int directoryRepos = seedDirectoryOwners(today, now, ordinal);
    log.info(
        "Seeded northstar mock fixture: owner={} repos={} trafficDays={} directoryOwners={} directoryRepos={} (full metrics)",
        OWNER_LOGIN,
        REPOS.size(),
        TRAFFIC_DAYS,
        DIRECTORY_OWNER_COUNT,
        directoryRepos);
  }

  /**
   * Published owners ({@code atlas-01}…{@code atlas-30}, 1–2 repos each) with the same rich ~30d
   * metric surface as northstar — traffic/stats, referrers/paths, search, topics, growth events —
   * for homepage cards, DB load and variant testing.
   */
  private int seedDirectoryOwners(LocalDate today, Instant now, int startingOrdinal) {
    int repos = 0;
    int ordinal = startingOrdinal;
    long nextRepoGithubId = DIRECTORY_REPO_GITHUB_ID_BASE;
    for (int index = 0; index < DIRECTORY_OWNER_COUNT; index++) {
      String login = directoryOwnerLogin(index);
      long ownerId = upsertOwner(DIRECTORY_OWNER_GITHUB_ID_BASE + index, login);
      int repoCount = index % 2 == 0 ? 2 : 1;
      for (int repoIndex = 0; repoIndex < repoCount; repoIndex++) {
        String repoName = repoIndex == 0 ? "core" : "tools";
        String language = DIRECTORY_LANGUAGES.get(index % DIRECTORY_LANGUAGES.size());
        int stars = 12 + index * 3 + repoIndex * 5;
        int watchers = 2 + index % 7;
        int forks = 1 + index % 5;
        RepoSpec spec =
            new RepoSpec(
                nextRepoGithubId++,
                login,
                repoName,
                false,
                language,
                stars,
                watchers,
                forks,
                index % 4,
                directoryTopics(index, repoIndex));
        seedRichRepository(ownerId, spec, ordinal, today, now);
        ordinal++;
        repos++;
      }
    }
    return repos;
  }

  private void seedRichRepository(
      long ownerId, RepoSpec spec, int ordinal, LocalDate today, Instant now) {
    long repositoryId = upsertRepository(ownerId, spec, now);
    boolean publishPublic = !spec.privateRepo();
    publishAndTrack(repositoryId, publishPublic, now);
    seedMetrics(repositoryId, ordinal, today, spec);
    if (seedContributorsForOwner(spec.ownerLogin())) {
      seedContributorLogins(repositoryId, today, contributorCount(spec.githubId()));
    }
    seedReferrersAndPaths(repositoryId, ordinal, today, spec);
    seedSearch(repositoryId, ordinal, today, spec, publishPublic);
    seedTopics(repositoryId, ordinal, today, spec);
    seedEvents(repositoryId, ordinal, today, spec);
  }

  /**
   * Owner-directory Contributors stays "—" unless every published repo of the owner has complete
   * login collection. Seed ~30% of atlas owners (and northstar) so those cards show 1..20.
   */
  static boolean seedContributorsForOwner(String ownerLogin) {
    if (OWNER_LOGIN.equals(ownerLogin)) {
      return true;
    }
    if (ownerLogin != null && ownerLogin.startsWith("atlas-")) {
      int n = Integer.parseInt(ownerLogin.substring("atlas-".length()));
      return n % 3 == 1;
    }
    return false;
  }

  /** Deterministic contributor headcount in 1..20. */
  static int contributorCount(long githubId) {
    return 1 + (int) (githubId % 20L);
  }

  private void seedContributorLogins(long repositoryId, LocalDate today, int contributors) {
    for (int dayIndex = 0; dayIndex < TRAFFIC_DAYS; dayIndex++) {
      LocalDate date = today.minusDays(TRAFFIC_DAYS - 1L - dayIndex);
      jdbc.sql(
              """
              UPDATE repository_daily_stats SET
                contributors = :contributors,
                contributor_logins_collected = TRUE
              WHERE repository_id = :repositoryId AND stat_date = :statDate
              """)
          .param("contributors", contributors)
          .param("repositoryId", repositoryId)
          .param("statDate", date)
          .update();
      jdbc.sql(
              """
              DELETE FROM repository_daily_contributor_login
              WHERE repository_id = :repositoryId AND stat_date = :statDate
              """)
          .param("repositoryId", repositoryId)
          .param("statDate", date)
          .update();
      for (int i = 1; i <= contributors; i++) {
        jdbc.sql(
                """
                INSERT INTO repository_daily_contributor_login (repository_id, stat_date, login)
                VALUES (:repositoryId, :statDate, :login)
                """)
            .param("repositoryId", repositoryId)
            .param("statDate", date)
            .param("login", "contrib-" + i)
            .update();
      }
    }
  }

  private long upsertOwner(long githubId, String login) {
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
        .param("githubId", githubId)
        .param("login", login)
        .param("avatarUrl", "https://avatars.githubusercontent.com/u/" + githubId + "?v=4")
        .param("htmlUrl", "https://github.com/" + login)
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
        .param("fullName", spec.ownerLogin() + "/" + spec.name())
        .param(
            "description",
            "Deterministic mock repository "
                + spec.ownerLogin()
                + "/"
                + spec.name()
                + " for RGM UI regression")
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

  private void seedReferrersAndPaths(
      long repositoryId, int ordinal, LocalDate today, RepoSpec spec) {
    String full = spec.ownerLogin() + "/" + spec.name();
    for (int dayIndex = 0; dayIndex < TRAFFIC_DAYS; dayIndex++) {
      LocalDate date = today.minusDays(TRAFFIC_DAYS - 1L - dayIndex);
      Instant snapshotAt = date.atTime(LocalTime.of(10, 15)).toInstant(ZoneOffset.UTC);
      for (int i = 0; i < REFERRERS.size(); i++) {
        String referrer = REFERRERS.get(i);
        jdbc.sql(
                """
                INSERT INTO traffic_referrer_snapshot (
                  repository_id, snapshot_date, snapshot_at, referrer, views, unique_visitors
                ) VALUES (
                  :repositoryId, :snapshotDate, :snapshotAt, :referrer, :views, :uniques
                )
                ON CONFLICT (repository_id, snapshot_date, referrer) DO UPDATE SET
                  snapshot_at = EXCLUDED.snapshot_at,
                  views = EXCLUDED.views,
                  unique_visitors = EXCLUDED.unique_visitors
                """)
            .param("repositoryId", repositoryId)
            .param("snapshotDate", date)
            .param("snapshotAt", Timestamp.from(snapshotAt))
            .param("referrer", referrer)
            .param("views", referrerViews(ordinal, dayIndex, i))
            .param("uniques", referrerUniques(ordinal, dayIndex, i))
            .update();
      }
      upsertPath(
          repositoryId,
          date,
          snapshotAt,
          "/" + full,
          spec.name(),
          pathViews(ordinal, dayIndex, 0),
          pathUniques(ordinal, dayIndex, 0),
          false);
      upsertPath(
          repositoryId,
          date,
          snapshotAt,
          "/" + full + "/blob/main/README.md",
          "README",
          pathViews(ordinal, dayIndex, 1),
          pathUniques(ordinal, dayIndex, 1),
          false);
      upsertPath(
          repositoryId,
          date,
          snapshotAt,
          "/" + full + "/issues",
          "Issues",
          pathViews(ordinal, dayIndex, 2),
          pathUniques(ordinal, dayIndex, 2),
          false);
      upsertPath(
          repositoryId,
          date,
          snapshotAt,
          "/" + full + "/graphs/traffic",
          "Traffic",
          pathViews(ordinal, dayIndex, 3),
          pathUniques(ordinal, dayIndex, 3),
          true);
    }
  }

  private void upsertPath(
      long repositoryId,
      LocalDate date,
      Instant snapshotAt,
      String path,
      String title,
      int views,
      int uniques,
      boolean servicePath) {
    jdbc.sql(
            """
            INSERT INTO traffic_path_snapshot (
              repository_id, snapshot_date, snapshot_at, path, title, views, unique_visitors, service_path
            ) VALUES (
              :repositoryId, :snapshotDate, :snapshotAt, :path, :title, :views, :uniques, :servicePath
            )
            ON CONFLICT (repository_id, snapshot_date, path) DO UPDATE SET
              snapshot_at = EXCLUDED.snapshot_at,
              title = EXCLUDED.title,
              views = EXCLUDED.views,
              unique_visitors = EXCLUDED.unique_visitors,
              service_path = EXCLUDED.service_path
            """)
        .param("repositoryId", repositoryId)
        .param("snapshotDate", date)
        .param("snapshotAt", Timestamp.from(snapshotAt))
        .param("path", path)
        .param("title", title)
        .param("views", views)
        .param("uniques", uniques)
        .param("servicePath", servicePath)
        .update();
  }

  private void seedSearch(
      long repositoryId, int ordinal, LocalDate today, RepoSpec spec, boolean publishPublic) {
    String primaryTopic = spec.topics().getFirst();
    long primaryId =
        upsertSearchQuery(
            repositoryId,
            "Primary",
            primaryTopic + " " + spec.language().toLowerCase() + " stars:>10",
            publishPublic);
    long secondaryId =
        upsertSearchQuery(
            repositoryId,
            "Discovery",
            "topic:" + primaryTopic + " language:" + spec.language(),
            publishPublic);
    seedRankRuns(repositoryId, primaryId, ordinal, today, false, 0);
    seedRankRuns(repositoryId, secondaryId, ordinal, today, false, 1);
  }

  private long upsertSearchQuery(
      long repositoryId, String name, String query, boolean publicEnabled) {
    Optional<Long> existing =
        jdbc.sql(
                """
                SELECT id FROM search_query
                WHERE repository_id = :repositoryId
                  AND lower(btrim(regexp_replace(query, '\\s+', ' ', 'g'))) =
                      lower(btrim(regexp_replace(:query, '\\s+', ' ', 'g')))
                """)
            .param("repositoryId", repositoryId)
            .param("query", query)
            .query(Long.class)
            .optional();
    if (existing.isPresent()) {
      jdbc.sql(
              """
              UPDATE search_query SET
                name = :name,
                query = :query,
                enabled = TRUE,
                result_limit = 50,
                public_enabled = :publicEnabled,
                updated_at = NOW()
              WHERE id = :id
              """)
          .param("name", name)
          .param("query", query)
          .param("publicEnabled", publicEnabled)
          .param("id", existing.get())
          .update();
      return existing.get();
    }
    return jdbc.sql(
            """
            INSERT INTO search_query (
              repository_id, name, query, enabled, result_limit, public_enabled
            ) VALUES (
              :repositoryId, :name, :query, TRUE, 50, :publicEnabled
            )
            RETURNING id
            """)
        .param("repositoryId", repositoryId)
        .param("name", name)
        .param("query", query)
        .param("publicEnabled", publicEnabled)
        .query(Long.class)
        .single();
  }

  private void seedTopics(long repositoryId, int ordinal, LocalDate today, RepoSpec spec) {
    jdbc.sql("DELETE FROM repository_topics WHERE repository_id = :id")
        .param("id", repositoryId)
        .update();
    for (String topic : spec.topics()) {
      jdbc.sql(
              """
              INSERT INTO repository_topics (repository_id, topic)
              VALUES (:repositoryId, :topic)
              """)
          .param("repositoryId", repositoryId)
          .param("topic", topic)
          .update();
      long allWatch = upsertTopicWatch(repositoryId, topic, null);
      long languageWatch = upsertTopicWatch(repositoryId, topic, spec.language());
      seedRankRuns(repositoryId, allWatch, ordinal, today, true, 0);
      seedRankRuns(repositoryId, languageWatch, ordinal, today, true, 1);
    }
  }

  private long upsertTopicWatch(long repositoryId, String topic, String language) {
    Optional<Long> existing =
        jdbc.sql(
                """
                SELECT id FROM topic_watch
                WHERE repository_id = :repositoryId
                  AND lower(topic) = lower(:topic)
                  AND coalesce(lower(language), '') = coalesce(lower(:language), '')
                """)
            .param("repositoryId", repositoryId)
            .param("topic", topic)
            .param("language", language)
            .query(Long.class)
            .optional();
    if (existing.isPresent()) {
      jdbc.sql(
              """
              UPDATE topic_watch SET
                enabled = TRUE,
                result_limit = 50,
                updated_at = NOW()
              WHERE id = :id
              """)
          .param("id", existing.get())
          .update();
      return existing.get();
    }
    return jdbc.sql(
            """
            INSERT INTO topic_watch (repository_id, topic, language, result_limit)
            VALUES (:repositoryId, :topic, :language, 50)
            RETURNING id
            """)
        .param("repositoryId", repositoryId)
        .param("topic", topic)
        .param("language", language)
        .query(Long.class)
        .single();
  }

  private void seedRankRuns(
      long repositoryId,
      long definitionId,
      int ordinal,
      LocalDate today,
      boolean topics,
      int seriesOffset) {
    String table = topics ? "topic_run" : "search_run";
    String fk = topics ? "topic_watch_id" : "search_query_id";
    for (int dayIndex = 0; dayIndex < TRAFFIC_DAYS; dayIndex++) {
      LocalDate date = today.minusDays(TRAFFIC_DAYS - 1L - dayIndex);
      Instant snapshotAt = date.atTime(LocalTime.of(9, 30)).toInstant(ZoneOffset.UTC);
      int position = rankFor(ordinal, dayIndex, seriesOffset);
      int total = 80 + ordinal * 7 + seriesOffset * 11;
      jdbc.sql(
              """
              INSERT INTO %s (
                %s, repository_id, business_date, status,
                started_at, completed_at, snapshot_at, total_count, tracked_repository_position,
                public_scope_verified
              ) VALUES (
                :definitionId, :repositoryId, :businessDate, 'SUCCESS',
                :snapshotAt, :snapshotAt, :snapshotAt, :totalCount, :position,
                TRUE
              )
              ON CONFLICT (%s, business_date) DO UPDATE SET
                status = 'SUCCESS',
                started_at = EXCLUDED.started_at,
                completed_at = EXCLUDED.completed_at,
                snapshot_at = EXCLUDED.snapshot_at,
                total_count = EXCLUDED.total_count,
                tracked_repository_position = EXCLUDED.tracked_repository_position,
                public_scope_verified = TRUE,
                error_code = NULL,
                error_message = NULL,
                updated_at = NOW()
              """
                  .formatted(table, fk, fk))
          .param("definitionId", definitionId)
          .param("repositoryId", repositoryId)
          .param("businessDate", date)
          .param("snapshotAt", Timestamp.from(snapshotAt))
          .param("totalCount", total)
          .param("position", position)
          .update();
    }
  }

  private void seedEvents(long repositoryId, int ordinal, LocalDate today, RepoSpec spec) {
    String html = "https://github.com/" + spec.ownerLogin() + "/" + spec.name();
    String idPrefix = spec.ownerLogin() + ":" + spec.githubId();
    // Automatic / system-style detections spread across the window.
    insertEvent(
        repositoryId,
        today.minusDays(28),
        "DISCOVERABILITY",
        "README_SIGNIFICANTLY_CHANGED",
        "README significantly changed",
        "Seeded README refresh for " + spec.ownerLogin() + "/" + spec.name(),
        html,
        "GITHUB",
        idPrefix + ":readme");
    insertEvent(
        repositoryId,
        today.minusDays(21),
        "DISCOVERABILITY",
        "TOPICS_CHANGED",
        "Repository topics changed",
        String.join(", ", spec.topics()),
        html,
        "GITHUB",
        idPrefix + ":topics");
    insertEvent(
        repositoryId,
        today.minusDays(14),
        "RELEASE",
        "RELEASE_PUBLISHED",
        "Release v1." + (ordinal % 20) + ".0",
        "v1." + (ordinal % 20) + ".0",
        html + "/releases/tag/v1." + (ordinal % 20) + ".0",
        "GITHUB",
        idPrefix + ":release:v1." + (ordinal % 20) + ".0");
    insertEvent(
        repositoryId,
        today.minusDays(10),
        "COMMUNITY",
        "EXTERNAL_PR_MERGED",
        "External pull request merged",
        "Improve " + spec.name() + " docs",
        html + "/pull/" + (10 + ordinal),
        "GITHUB",
        idPrefix + ":pr-merged");
    insertEvent(
        repositoryId,
        today.minusDays(7),
        "MILESTONE",
        "STAR_MILESTONE",
        "Reached " + nearestStarThreshold(spec.stars()) + " stars",
        null,
        html,
        "SYSTEM",
        idPrefix + ":stars:" + nearestStarThreshold(spec.stars()));
    // Promotion-style manual events (public highlight + Recent Growth Events).
    insertEvent(
        repositoryId,
        today.minusDays(5),
        "PROMOTION",
        "DEVTO_ARTICLE",
        "Dev.to: Introducing " + spec.name(),
        "Walkthrough of " + spec.name() + " for operators",
        "https://dev.to/" + spec.ownerLogin() + "/" + spec.name(),
        "MANUAL",
        idPrefix + ":devto");
    insertEvent(
        repositoryId,
        today.minusDays(2),
        "PROMOTION",
        "REDDIT_POST",
        "Reddit: " + spec.name() + " deep dive",
        "Community discussion for " + spec.name(),
        "https://reddit.com/r/programming/comments/" + spec.ownerLogin() + "-" + spec.name(),
        "MANUAL",
        idPrefix + ":reddit");
    if (ordinal % 2 == 0) {
      insertEvent(
          repositoryId,
          today.minusDays(1),
          "PROMOTION",
          "HACKER_NEWS_POST",
          "HN: " + spec.name(),
          null,
          "https://news.ycombinator.com/item?id=" + spec.ownerLogin() + spec.githubId(),
          "MANUAL",
          idPrefix + ":hn");
    }
  }

  private void insertEvent(
      long repositoryId,
      LocalDate day,
      String category,
      String type,
      String title,
      String description,
      String url,
      String source,
      String externalId) {
    Instant eventAt = day.atTime(LocalTime.of(15, 0)).toInstant(ZoneOffset.UTC);
    jdbc.sql(
            """
            INSERT INTO growth_event (
              repository_id, event_at, category, type, title, description, url, source, external_id
            ) VALUES (
              :repositoryId, :eventAt, :category, :type, :title, :description, :url, :source, :externalId
            )
            ON CONFLICT DO NOTHING
            """)
        .param("repositoryId", repositoryId)
        .param("eventAt", Timestamp.from(eventAt))
        .param("category", category)
        .param("type", type)
        .param("title", title)
        .param("description", description)
        .param("url", url)
        .param("source", source)
        .param("externalId", externalId)
        .update();
  }

  /** Matches WireMock northstar view scale: base grows by repo ordinal and day. */
  static int viewsFor(int repoOrdinal, int dayIndex) {
    return 40 + repoOrdinal * 19 + dayIndex * 3;
  }

  static int clonesFor(int repoOrdinal, int dayIndex) {
    return 2 + repoOrdinal + (dayIndex % 5);
  }

  /** Aligns with WireMock northstar-referrers.json base counts, nudged by day. */
  static int referrerViews(int repoOrdinal, int dayIndex, int referrerIndex) {
    int base =
        switch (referrerIndex) {
          case 0 -> 40 + repoOrdinal * 3;
          case 1 -> 25 + repoOrdinal * 2;
          case 2 -> 10 + repoOrdinal;
          case 3 -> 5 + (repoOrdinal % 5);
          default -> 8 + repoOrdinal;
        };
    return Math.max(1, base + dayIndex / 3);
  }

  static int referrerUniques(int repoOrdinal, int dayIndex, int referrerIndex) {
    int base =
        switch (referrerIndex) {
          case 0 -> 20 + repoOrdinal;
          case 1 -> 12 + repoOrdinal;
          case 2 -> 6 + (repoOrdinal % 4);
          case 3 -> 3;
          default -> 4;
        };
    return Math.max(1, Math.min(referrerViews(repoOrdinal, dayIndex, referrerIndex), base + dayIndex / 5));
  }

  /** Aligns with WireMock northstar-paths.json base counts, nudged by day. */
  static int pathViews(int repoOrdinal, int dayIndex, int pathIndex) {
    int base =
        switch (pathIndex) {
          case 0 -> 50 + repoOrdinal * 4;
          case 1 -> 18 + repoOrdinal;
          case 2 -> 7 + repoOrdinal;
          default -> 3;
        };
    return Math.max(1, base + dayIndex / 4);
  }

  static int pathUniques(int repoOrdinal, int dayIndex, int pathIndex) {
    int base =
        switch (pathIndex) {
          case 0 -> 22 + repoOrdinal;
          case 1 -> 10;
          case 2 -> 5;
          default -> 2;
        };
    return Math.max(1, Math.min(pathViews(repoOrdinal, dayIndex, pathIndex), base + dayIndex / 6));
  }

  /** Rank improves over the 30d window and stays inside the default Top-50 limit. */
  static int rankFor(int repoOrdinal, int dayIndex, int seriesOffset) {
    return Math.min(50, Math.max(1, 35 - dayIndex + (repoOrdinal % 6) + seriesOffset * 2));
  }

  static int nearestStarThreshold(int stars) {
    if (stars >= 100) {
      return 100;
    }
    if (stars >= 50) {
      return 50;
    }
    return 10;
  }

  /** Deterministic public directory login: {@code atlas-01} … {@code atlas-30}. */
  static String directoryOwnerLogin(int index) {
    return "atlas-%02d".formatted(index + 1);
  }

  static List<String> directoryTopics(int ownerIndex, int repoIndex) {
    List<String> bundle =
        DIRECTORY_TOPIC_BUNDLES.get(
            (ownerIndex + repoIndex) % DIRECTORY_TOPIC_BUNDLES.size());
    return List.copyOf(bundle);
  }

  private static RepoSpec northstarRepo(
      long githubId,
      String name,
      boolean privateRepo,
      String language,
      int stars,
      int watchers,
      int forks,
      int openIssues,
      String topicA,
      String topicB,
      String topicC) {
    return new RepoSpec(
        githubId,
        OWNER_LOGIN,
        name,
        privateRepo,
        language,
        stars,
        watchers,
        forks,
        openIssues,
        List.of(topicA, topicB, topicC));
  }

  private record RepoSpec(
      long githubId,
      String ownerLogin,
      String name,
      boolean privateRepo,
      String language,
      int stars,
      int watchers,
      int forks,
      int openIssues,
      List<String> topics) {}
}
