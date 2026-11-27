package com.kholodilin.repogrowth.catalog.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC helpers for HTML pretty-path gating and the published owners directory. */
@Repository
public class PublicHtmlJdbcRepository {

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate sql;

  public PublicHtmlJdbcRepository(JdbcTemplate jdbc) {
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
            + " AND lower(o.login) NOT IN (:reserved) AND lower(o.login) NOT LIKE 'swagger-ui%' AND o.login ~ '^[A-Za-z0-9][A-Za-z0-9-]{0,99}$'),latest AS (SELECT DISTINCT ON (s.repository_id) s.* FROM repository_daily_stats s JOIN published p ON p.id=s.repository_id WHERE s.stat_date<=CURRENT_DATE ORDER BY s.repository_id,s.stat_date DESC),totals AS (SELECT p.owner_id,count(*) AS repositories,count(s.repository_id)=count(*) AS complete,CASE WHEN count(s.repository_id)=count(*) THEN sum(s.stars) END AS stars,CASE WHEN count(s.repository_id)=count(*) THEN sum(s.forks) END AS forks,CASE WHEN count(s.repository_id)=count(*) THEN sum(s.watchers) END AS watchers FROM published p LEFT JOIN latest s ON s.repository_id=p.id GROUP BY p.owner_id),contributors AS (SELECT p.owner_id,count(DISTINCT lower(c.login)) AS known,bool_and(coalesce(s.contributor_logins_collected,false) AND s.contributors=(SELECT count(*) FROM repository_daily_contributor_login x WHERE x.repository_id=s.repository_id AND x.stat_date=s.stat_date)) AS complete FROM published p LEFT JOIN latest s ON s.repository_id=p.id LEFT JOIN repository_daily_contributor_login c ON c.repository_id=s.repository_id AND c.stat_date=s.stat_date GROUP BY p.owner_id) SELECT o.id AS \"ownerId\",o.login,o.owner_type AS type,'/'||o.login AS \"canonicalPath\",t.repositories,t.stars,t.forks,t.watchers,t.complete,CASE WHEN c.complete THEN c.known END AS contributors,c.complete AS \"contributorsComplete\" FROM totals t JOIN github_owner o ON o.id=t.owner_id JOIN contributors c ON c.owner_id=o.id ORDER BY "
            + column
            + " "
            + order
            + " NULLS LAST,o.id LIMIT :limit OFFSET :offset";
    return sql.queryForList(query, p);
  }
}
