package com.kholodilin.repogrowth.collection.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for collection-request status projections. */
@Repository
public class CollectionRequestStatusJdbcRepository {

  private final JdbcTemplate jdbc;

  public CollectionRequestStatusJdbcRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> findRequest(UUID id) {
    return jdbc.queryForList(
        "SELECT public_id::text AS \"requestId\",state,created_at AS \"createdAt\",completed_at AS \"completedAt\" FROM collection_request WHERE public_id=?",
        id);
  }

  public List<Map<String, Object>> findTasks(UUID requestPublicId) {
    return jdbc.queryForList(
        "SELECT t.public_id::text AS \"jobId\",t.task_type AS type,t.state,t.current_attempt_id::text AS \"attemptId\" FROM collection_task t JOIN collection_request r ON r.id=t.request_id WHERE r.public_id=? ORDER BY t.id",
        requestPublicId);
  }
}
