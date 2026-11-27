package com.kholodilin.repogrowth.collection.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for admin-only active collection progress projections. */
@Repository
public class AdminCollectionProgressJdbcRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public AdminCollectionProgressJdbcRepository(JdbcTemplate jdbc) {
    this.jdbc = new NamedParameterJdbcTemplate(jdbc);
  }

  public List<Map<String, Object>> activeTasks(List<Long> repositoryIds) {
    return jdbc.queryForList(
        "SELECT r.repository_id,t.task_type,t.state FROM collection_request r JOIN collection_task t ON t.request_id=r.id WHERE r.state='ACTIVE' AND r.repository_id IN (:ids) ORDER BY t.id",
        Map.of("ids", repositoryIds));
  }
}
