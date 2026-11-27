package com.kholodilin.repogrowth.collection.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for idempotent collection-request command bindings. */
@Repository
public class CollectionRequestCommandJdbcRepository {

  private final JdbcTemplate jdbc;

  public CollectionRequestCommandJdbcRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> findBinding(String commandKey) {
    return jdbc.queryForList(
        "SELECT c.*,r.public_id FROM collection_request_command c JOIN collection_request r ON r.id=c.request_id WHERE c.command_key=?",
        commandKey);
  }

  public List<UUID> taskPublicIds(long requestId) {
    return jdbc.queryForList(
        "SELECT public_id FROM collection_task WHERE request_id=? ORDER BY id",
        UUID.class,
        requestId);
  }

  public void insertBinding(
      String commandKey, long actorId, long repositoryId, boolean coalesced, UUID requestPublicId) {
    jdbc.update(
        "INSERT INTO collection_request_command(command_key,actor_id,repository_id,request_id,coalesced) SELECT ?,?,?,id,? FROM collection_request WHERE public_id=?",
        commandKey,
        actorId,
        repositoryId,
        coalesced,
        requestPublicId);
  }
}
