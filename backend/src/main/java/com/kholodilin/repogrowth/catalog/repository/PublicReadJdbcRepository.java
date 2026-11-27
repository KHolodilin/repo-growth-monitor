package com.kholodilin.repogrowth.catalog.repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC projections for public (anonymous) dashboard reads. */
@Repository
public class PublicReadJdbcRepository {

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate sql;

  public PublicReadJdbcRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.sql = new NamedParameterJdbcTemplate(jdbc);
  }

  public String gateSql() {
    return "r.public_dashboard_enabled AND r.visibility='PUBLIC' AND r.visibility_verified_at<=clock_timestamp() AND r.visibility_verified_at>clock_timestamp()-(:age*interval '1 millisecond')";
  }

  public List<Long> scopedRepositoryIds(
      long ownerId, Long repositoryId, String moduleFlag, long verificationAgeMillis) {
    Map<String, Object> p = new HashMap<>();
    p.put("age", verificationAgeMillis);
    p.put("owner", ownerId);
    p.put("repository", repositoryId);
    return sql.queryForList(
        "SELECT r.id FROM repository r WHERE r.owner_id=:owner AND (:repository::bigint IS NULL OR r.id=:repository) AND "
            + gateSql()
            + " AND r."
            + moduleFlag
            + " ORDER BY r.id",
        p,
        Long.class);
  }

  public List<Map<String, Object>> repositoryPage(
      List<Long> ids,
      long after,
      int limit,
      LocalDate from,
      LocalDate to,
      LocalDate trafficTo,
      long verificationAgeMillis) {
    Map<String, Object> p = new HashMap<>();
    p.put("age", verificationAgeMillis);
    p.put("ids", ids);
    p.put("after", after);
    p.put("limit", limit);
    p.put("from", from);
    p.put("to", to);
    p.put("trafficTo", trafficTo);
    return sql.queryForList(
        "SELECT s.stars,s.forks,t.views,t.visitors,t.clones,t.\"trafficComplete\" AS \"trafficComplete\",r.id,r.github_id::text AS \"githubId\",r.name,r.description,r.language,r.archived,r.publication_revision AS \"publicationRevision\",r.enriched_at AS \"lastObservedAt\",coalesce(r.last_commit_at,r.github_pushed_at) AS \"activityAt\" FROM repository r LEFT JOIN LATERAL (SELECT stars,forks FROM repository_daily_stats WHERE repository_id=r.id AND stat_date<=:to ORDER BY stat_date DESC LIMIT 1) s ON true LEFT JOIN LATERAL (SELECT sum(views) AS views,sum(unique_visitors) AS visitors,sum(clones) AS clones,count(*)=(:trafficTo::date-:from::date+1) AS \"trafficComplete\" FROM traffic_daily WHERE repository_id=r.id AND traffic_date BETWEEN :from AND :trafficTo AND r.public_traffic_enabled) t ON true WHERE r.id IN (:ids) AND r.id>:after ORDER BY r.id LIMIT :limit",
        p);
  }

  public LocalDate earliestDate(List<Long> ids, boolean traffic) {
    String table = traffic ? "traffic_daily" : "repository_daily_stats";
    String date = traffic ? "traffic_date" : "stat_date";
    return sql.queryForObject(
        "SELECT min(" + date + ") FROM " + table + " WHERE repository_id IN (:ids)",
        Map.of("ids", ids),
        LocalDate.class);
  }

  public List<Map<String, Object>> latestDailyStats(List<Long> ids, LocalDate to) {
    return sql.queryForList(
        "SELECT DISTINCT ON (repository_id) repository_id,stars,forks,watchers,contributors,contributor_logins_collected,stat_date FROM repository_daily_stats WHERE repository_id IN (:ids) AND stat_date<=:to ORDER BY repository_id,stat_date DESC",
        Map.of("ids", ids, "to", to));
  }

  public List<Map<String, Object>> contributorLogins(List<Long> ids, List<LocalDate> tipDates) {
    return sql.queryForList(
        "SELECT repository_id,stat_date,login FROM repository_daily_contributor_login WHERE repository_id IN (:ids) AND stat_date IN (:tipDates)",
        Map.of("ids", ids, "tipDates", tipDates));
  }

  public <T> List<T> successfulRanks(List<Long> ids, LocalDate to, RowMapper<T> mapper) {
    return sql.query(
        "SELECT r.id,r.full_name,q.name AS query,s.business_date,s.tracked_repository_position FROM search_run s JOIN search_query q ON q.id=s.search_query_id AND q.repository_id=s.repository_id JOIN repository r ON r.id=s.repository_id WHERE r.id IN (:ids) AND q.public_enabled AND s.status='SUCCESS' AND s.tracked_repository_position IS NOT NULL AND s.business_date<=:to",
        Map.of("ids", ids, "to", to),
        mapper);
  }

  public <T> List<T> promotionCounts(
      List<Long> ids, LocalDate from, LocalDate to, RowMapper<T> mapper) {
    return sql.query(
        "SELECT r.id,r.full_name,count(*) AS count FROM growth_event e JOIN repository r ON r.id=e.repository_id WHERE r.id IN (:ids) AND e.category='PROMOTION' AND (e.event_at AT TIME ZONE 'UTC')::date BETWEEN :from AND :to GROUP BY r.id,r.full_name",
        Map.of("ids", ids, "from", from, "to", to),
        mapper);
  }

  public <T> List<T> visitorRows(
      List<Long> ids,
      LocalDate from,
      LocalDate to,
      LocalDate previousFrom,
      LocalDate previousTo,
      RowMapper<T> mapper) {
    Map<String, Object> p = new HashMap<>();
    p.put("ids", ids);
    p.put("from", from);
    p.put("to", to);
    p.put("previousFrom", previousFrom);
    p.put("previousTo", previousTo);
    return sql.query(
        "SELECT r.id,r.full_name,count(t.traffic_date) FILTER(WHERE t.traffic_date BETWEEN :from AND :to) AS observed,count(t.traffic_date) FILTER(WHERE t.traffic_date BETWEEN :previousFrom AND :previousTo) AS prior_observed,sum(t.unique_visitors) FILTER(WHERE t.traffic_date BETWEEN :from AND :to) AS visitors,sum(t.unique_visitors) FILTER(WHERE t.traffic_date BETWEEN :previousFrom AND :previousTo) AS prior FROM repository r LEFT JOIN traffic_daily t ON t.repository_id=r.id AND t.traffic_date BETWEEN :previousFrom AND :to WHERE r.id IN (:ids) AND r.public_traffic_enabled GROUP BY r.id,r.full_name",
        p,
        mapper);
  }

  public List<Map<String, Object>> statsHistory(
      List<Long> ids, LocalDate from, LocalDate to, int limit) {
    Map<String, Object> p = new HashMap<>();
    p.put("ids", ids);
    p.put("from", from);
    p.put("to", to);
    p.put("limit", limit);
    return sql.queryForList(
        "SELECT stat_date AS date,sum(stars) AS stars,sum(forks) AS forks,sum(watchers) AS watchers,count(*) AS observed FROM repository_daily_stats WHERE repository_id IN (:ids) AND stat_date BETWEEN :from AND :to GROUP BY stat_date ORDER BY stat_date DESC LIMIT :limit",
        p);
  }

  public List<Map<String, Object>> statsHistoryContributorCounts(
      List<Long> ids, List<?> dates, int expected) {
    Map<String, Object> p = new HashMap<>();
    p.put("ids", ids);
    p.put("dates", dates);
    p.put("expected", expected);
    return sql.queryForList(
        "SELECT s.stat_date AS date,count(DISTINCT lower(c.login)) AS contributors,bool_and(s.contributor_logins_collected AND s.contributors=(SELECT count(*) FROM repository_daily_contributor_login x WHERE x.repository_id=s.repository_id AND x.stat_date=s.stat_date)) AND count(DISTINCT s.repository_id)=:expected AS complete FROM repository_daily_stats s LEFT JOIN repository_daily_contributor_login c ON c.repository_id=s.repository_id AND c.stat_date=s.stat_date WHERE s.repository_id IN (:ids) AND s.stat_date IN (:dates) GROUP BY s.stat_date",
        p);
  }

  public Map<String, Object> trafficTotals(List<Long> ids, LocalDate from, LocalDate to) {
    return sql.queryForMap(
        "SELECT count(*) AS observed,coalesce(sum(views),0) AS views,coalesce(sum(unique_visitors),0) AS visitors,coalesce(sum(clones),0) AS clones,coalesce(sum(unique_cloners),0) AS \"uniqueCloners\" FROM traffic_daily WHERE repository_id IN (:ids) AND traffic_date BETWEEN :from AND :to",
        Map.of("ids", ids, "from", from, "to", to));
  }

  public Long trafficTipCount(List<Long> ids, LocalDate tip) {
    return sql.queryForObject(
        "SELECT count(*) FROM traffic_daily WHERE repository_id IN (:ids) AND traffic_date=:tip",
        Map.of("ids", ids, "tip", tip),
        Long.class);
  }

  public List<Map<String, Object>> trafficHistory(
      List<Long> ids, LocalDate from, LocalDate to, int limit) {
    Map<String, Object> p = new HashMap<>();
    p.put("ids", ids);
    p.put("from", from);
    p.put("to", to);
    p.put("limit", limit);
    return sql.queryForList(
        "SELECT traffic_date AS date,sum(views) AS views,sum(unique_visitors) AS visitors,sum(clones) AS clones,sum(unique_cloners) AS \"uniqueCloners\",count(*) AS observed FROM traffic_daily WHERE repository_id IN (:ids) AND traffic_date BETWEEN :from AND :to GROUP BY traffic_date ORDER BY traffic_date DESC LIMIT :limit",
        p);
  }

  public List<Map<String, Object>> trafficShares(List<Long> ids, List<LocalDate> dates) {
    return sql.queryForList(
        "SELECT t.traffic_date AS date,r.full_name AS \"fullName\",t.views,t.unique_visitors AS visitors,t.clones FROM traffic_daily t JOIN repository r ON r.id=t.repository_id WHERE t.repository_id IN (:ids) AND t.traffic_date IN (:dates)",
        Map.of("ids", ids, "dates", dates));
  }

  public List<Map<String, Object>> statShares(List<Long> ids, List<LocalDate> dates) {
    return sql.queryForList(
        "SELECT s.stat_date AS date,r.full_name AS \"fullName\",s.stars,s.forks,s.watchers,s.contributors FROM repository_daily_stats s JOIN repository r ON r.id=s.repository_id WHERE s.repository_id IN (:ids) AND s.stat_date IN (:dates)",
        Map.of("ids", ids, "dates", dates));
  }

  public List<Map<String, Object>> latestSources(
      List<Long> ids, boolean paths, long after, int limit) {
    String table = paths ? "traffic_path_snapshot" : "traffic_referrer_snapshot";
    String column = paths ? "path" : "referrer";
    String family = paths ? "POPULAR_PATHS" : "REFERRERS";
    Map<String, Object> p = new HashMap<>();
    p.put("ids", ids);
    p.put("after", after);
    p.put("limit", limit);
    return sql.queryForList(
        "SELECT s.id,s.repository_id AS \"repositoryId\",s."
            + column
            + " AS label,s.views,s.unique_visitors AS visitors,s.snapshot_at AS \"observedAt\" FROM "
            + table
            + " s WHERE s.repository_id IN (:ids) AND s.id>:after "
            + (paths ? "AND NOT s.service_path " : "")
            + "AND s.snapshot_date=(SELECT max(day) FROM (SELECT snapshot_date AS day FROM "
            + table
            + " WHERE repository_id=s.repository_id UNION ALL SELECT business_date FROM collector_snapshot_header WHERE repository_id=s.repository_id AND family='"
            + family
            + "') dates) ORDER BY s.id LIMIT :limit",
        p);
  }

  public List<Map<String, Object>> definitions(
      List<Long> ids, boolean topics, long after, int limit) {
    Map<String, Object> p = new HashMap<>();
    p.put("ids", ids);
    p.put("after", after);
    p.put("limit", limit);
    return sql.queryForList(
        "SELECT id,repository_id AS \"repositoryId\","
            + (topics
                ? "topic,language,result_limit AS \"resultLimit\""
                : "name,query,result_limit AS \"resultLimit\"")
            + " FROM "
            + (topics ? "topic_watch" : "search_query")
            + " WHERE repository_id IN (:ids) AND "
            + (topics ? "enabled" : "public_enabled")
            + " AND id>:after ORDER BY id LIMIT :limit",
        p);
  }

  public List<Map<String, Object>> parentDefinitionOrRun(boolean topics, long id, boolean run) {
    String table =
        run ? (topics ? "topic_run" : "search_run") : topics ? "topic_watch" : "search_query";
    String join =
        run
            ? " JOIN "
                + (topics
                    ? "topic_watch q ON q.id=t.topic_watch_id"
                    : "search_query q ON q.id=t.search_query_id")
            : "";
    return jdbc.queryForList(
        "SELECT t.repository_id,"
            + (run ? "q.id" : "t.id")
            + " AS definition FROM "
            + table
            + " t"
            + join
            + " WHERE t.id=?"
            + (run ? " AND t.repository_id=q.repository_id" : ""),
        id);
  }

  public List<Map<String, Object>> topicWatchByTopic(List<Long> ids, String topic) {
    return sql.queryForList(
        "SELECT id FROM topic_watch WHERE repository_id IN (:ids) AND topic=:topic AND enabled ORDER BY id LIMIT 1",
        Map.of("ids", ids, "topic", topic));
  }

  public List<Map<String, Object>> definitionById(boolean topics, long id) {
    return jdbc.queryForList(
        "SELECT id,repository_id AS \"repositoryId\","
            + (topics
                ? "topic,language,result_limit AS \"resultLimit\""
                : "name,query,result_limit AS \"resultLimit\"")
            + " FROM "
            + (topics ? "topic_watch" : "search_query")
            + " WHERE id=?",
        id);
  }

  public List<Map<String, Object>> rankHistory(
      boolean topics, long definition, long before, int limit) {
    return jdbc.queryForList(
        "SELECT id,business_date AS date,snapshot_at AS \"observedAt\",total_count AS \"totalResults\",tracked_repository_position AS position FROM "
            + (topics ? "topic_run" : "search_run")
            + " WHERE "
            + (topics ? "topic_watch_id" : "search_query_id")
            + "=? AND snapshot_at IS NOT NULL AND public_scope_verified AND (?=0 OR id<?) ORDER BY id DESC LIMIT ?",
        definition,
        before,
        before,
        limit);
  }

  public Boolean runPubliclyVerified(boolean topics, long run) {
    String prefix = topics ? "topic" : "search";
    return jdbc.queryForObject(
        "SELECT snapshot_at IS NOT NULL AND public_scope_verified FROM "
            + prefix
            + "_run WHERE id=?",
        Boolean.class,
        run);
  }

  public List<Map<String, Object>> rankResults(boolean topics, long run, long after, int limit) {
    String prefix = topics ? "topic" : "search";
    return jdbc.queryForList(
        "SELECT position AS id,position,github_repository_id::text AS \"githubId\",full_name AS \"fullName\",stars,forks,language,description FROM "
            + prefix
            + "_result WHERE "
            + prefix
            + "_run_id=? AND position>? ORDER BY position LIMIT ?",
        run,
        after,
        limit);
  }

  public List<Map<String, Object>> events(List<Long> ids, long before, int limit) {
    return sql.queryForList(
        "SELECT id,repository_id AS \"repositoryId\",category,source,type,event_at AS \"eventAt\",title FROM growth_event WHERE repository_id IN (:ids) AND (:before=0 OR id<:before) ORDER BY id DESC LIMIT :limit",
        Map.of("ids", ids, "before", before, "limit", limit));
  }

  public List<Map<String, Object>> sourceHistory(
      List<Long> ids, boolean paths, LocalDate from, LocalDate to, long before, int limit) {
    String table = paths ? "traffic_path_snapshot" : "traffic_referrer_snapshot";
    String key = paths ? "path" : "referrer";
    Map<String, Object> p = new HashMap<>();
    p.put("ids", ids);
    p.put("from", from);
    p.put("to", to);
    p.put("before", before);
    p.put("limit", limit);
    return sql.queryForList(
        "SELECT id,repository_id AS \"repositoryId\",snapshot_date AS date,"
            + key
            + " AS label,views,unique_visitors AS visitors FROM "
            + table
            + " WHERE repository_id IN (:ids) AND snapshot_date BETWEEN :from AND :to AND (:before=0 OR id<:before) "
            + (paths ? "AND NOT service_path " : "")
            + "ORDER BY id DESC LIMIT :limit",
        p);
  }

  public List<Map<String, Object>> rankSeries(
      boolean topics, long definition, long before, int limit) {
    String prefix = topics ? "topic" : "search";
    return jdbc.queryForList(
        "SELECT s.id,r.business_date AS date,s.github_repository_id::text AS \"githubId\",s.full_name AS \"fullName\",s.position,s.stars,s.forks FROM "
            + prefix
            + "_result s JOIN "
            + prefix
            + "_run r ON r.id=s."
            + prefix
            + "_run_id WHERE r."
            + (topics ? "topic_watch_id" : "search_query_id")
            + "=? AND r.snapshot_at IS NOT NULL AND r.public_scope_verified AND (?=0 OR s.id<?) ORDER BY s.id DESC LIMIT ?",
        definition,
        before,
        before,
        limit);
  }

  public List<Map<String, Object>> competitor(boolean topics, long definition, long githubId) {
    String prefix = topics ? "topic" : "search";
    return jdbc.queryForList(
        "SELECT s.github_repository_id::text AS \"githubId\",s.full_name AS \"fullName\",s.description,s.language,s.stars,s.forks FROM "
            + prefix
            + "_result s JOIN "
            + prefix
            + "_run r ON r.id=s."
            + prefix
            + "_run_id WHERE r."
            + (topics ? "topic_watch_id" : "search_query_id")
            + "=? AND s.github_repository_id=? AND r.snapshot_at IS NOT NULL AND r.public_scope_verified ORDER BY r.business_date DESC LIMIT 1",
        definition,
        githubId);
  }

  public List<Map<String, Object>> competitorProfile(long githubId) {
    return jdbc.queryForList(
        "SELECT topics,archived,last_commit_at AS \"lastCommitAt\",last_release_at AS \"lastReleaseAt\",has_readme AS \"hasReadme\",has_license AS \"hasLicense\",has_social_preview AS \"hasSocialPreview\" FROM competitor_profile p WHERE p.github_repository_id=? AND EXISTS(SELECT 1 FROM repository v WHERE v.github_id=p.github_repository_id AND v.visibility='PUBLIC' AND v.visibility_verified_at>clock_timestamp()-interval '24 hours' AND v.visibility_verified_at<=clock_timestamp())",
        githubId);
  }

  public List<Long> latestPublicRunId(boolean topics, long definition) {
    return jdbc.queryForList(
        "SELECT id FROM "
            + (topics ? "topic_run" : "search_run")
            + " WHERE "
            + (topics ? "topic_watch_id" : "search_query_id")
            + "=? AND snapshot_at IS NOT NULL AND public_scope_verified ORDER BY business_date DESC LIMIT 1",
        Long.class,
        definition);
  }

  public Map<String, Object> ownerMetadata(long ownerId) {
    return jdbc.queryForMap(
        "SELECT github_id::text AS \"githubId\",login,owner_type AS type FROM github_owner WHERE id=?",
        ownerId);
  }

  public Map<String, Object> moduleFlags(List<Long> ids) {
    return sql.queryForMap(
        "SELECT bool_or(public_traffic_enabled) AS traffic,bool_or(public_referrers_enabled) AS referrers,bool_or(public_paths_enabled) AS paths,bool_or(public_topics_enabled) AS topics FROM repository WHERE id IN (:ids)",
        Map.of("ids", ids));
  }

  public Boolean hasPublicSearch(List<Long> ids) {
    return sql.queryForObject(
        "SELECT count(*)>0 FROM search_query WHERE repository_id IN (:ids) AND public_enabled",
        Map.of("ids", ids),
        Boolean.class);
  }

  public Timestamp maxEnrichedAt(List<Long> ids) {
    return sql.queryForObject(
        "SELECT max(enriched_at) FROM repository WHERE id IN (:ids)",
        Map.of("ids", ids),
        Timestamp.class);
  }

  public Map<String, Object> repositoryCard(long repositoryId) {
    return jdbc.queryForMap(
        "SELECT github_id::text AS \"githubId\",name,description,language,archived,publication_revision AS \"publicationRevision\" FROM repository WHERE id=?",
        repositoryId);
  }

  public List<String> repositoryPathPrefixes(long repositoryId) {
    return jdbc.queryForList(
        "SELECT '/'||owner_login||'/'||repository_name FROM repository_path_alias WHERE repository_id=? ORDER BY id DESC LIMIT 100",
        String.class,
        repositoryId);
  }

  public List<Map<String, Object>> ownersDirectory(
      String column,
      String order,
      long offset,
      int limit,
      long verificationAgeMillis,
      Set<String> reserved) {
    Map<String, Object> p = new HashMap<>();
    p.put("age", verificationAgeMillis);
    p.put("reserved", reserved);
    p.put("offset", offset);
    p.put("limit", limit);
    String query =
        "WITH published AS (SELECT r.* FROM repository r JOIN github_owner o ON o.id=r.owner_id WHERE "
            + gateSql()
            + " AND lower(o.login) NOT IN (:reserved) AND lower(o.login) NOT LIKE 'swagger-ui%' AND o.login ~ '^[A-Za-z0-9][A-Za-z0-9-]{0,99}$'),latest AS (SELECT DISTINCT ON (s.repository_id) s.* FROM repository_daily_stats s JOIN published p ON p.id=s.repository_id WHERE s.stat_date<=CURRENT_DATE ORDER BY s.repository_id,s.stat_date DESC),totals AS (SELECT p.owner_id,count(*) AS repositories,count(s.repository_id)=count(*) AS complete,CASE WHEN count(s.repository_id)=count(*) THEN sum(s.stars) END AS stars,CASE WHEN count(s.repository_id)=count(*) THEN sum(s.forks) END AS forks,CASE WHEN count(s.repository_id)=count(*) THEN sum(s.watchers) END AS watchers FROM published p LEFT JOIN latest s ON s.repository_id=p.id GROUP BY p.owner_id),contributors AS (SELECT p.owner_id,count(DISTINCT lower(c.login)) AS known,bool_and(coalesce(s.contributor_logins_collected,false) AND s.contributors=(SELECT count(*) FROM repository_daily_contributor_login x WHERE x.repository_id=s.repository_id AND x.stat_date=s.stat_date)) AS complete FROM published p LEFT JOIN latest s ON s.repository_id=p.id LEFT JOIN repository_daily_contributor_login c ON c.repository_id=s.repository_id AND c.stat_date=s.stat_date GROUP BY p.owner_id) SELECT o.id,o.login,o.owner_type AS type,'/'||o.login AS \"canonicalPath\",t.repositories,t.stars,t.forks,t.watchers,t.complete,CASE WHEN c.complete THEN c.known END AS contributors,c.complete AS \"contributorsComplete\" FROM totals t JOIN github_owner o ON o.id=t.owner_id JOIN contributors c ON c.owner_id=o.id ORDER BY "
            + column
            + " "
            + order
            + " NULLS LAST,o.id LIMIT :limit OFFSET :offset";
    return sql.queryForList(query, p);
  }
}
