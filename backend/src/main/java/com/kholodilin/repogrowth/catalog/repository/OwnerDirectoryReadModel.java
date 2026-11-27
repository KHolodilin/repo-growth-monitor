package com.kholodilin.repogrowth.catalog.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Narrow catalog read for the phase 1 owners directory skeleton. */
@Component
public class OwnerDirectoryReadModel {

  public record OwnerRow(long ownerId, String login, List<Long> repositoryIds) {}

  private final JdbcTemplate jdbc;

  public OwnerDirectoryReadModel(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<OwnerRow> list(String exactLogin) {
    String sql =
        """
        SELECT o.id AS owner_id, o.login, r.id AS repository_id
        FROM github_owner o
        JOIN repository r ON r.owner_id = o.id
        WHERE (?::text IS NULL OR lower(o.login) = lower(?))
        ORDER BY o.id, r.id
        """;
    Map<Long, OwnerRow> byId = new LinkedHashMap<>();
    jdbc.query(
        sql,
        rs -> {
          long ownerId = rs.getLong("owner_id");
          String login = rs.getString("login");
          long repositoryId = rs.getLong("repository_id");
          OwnerRow existing = byId.get(ownerId);
          if (existing == null) {
            List<Long> ids = new ArrayList<>();
            ids.add(repositoryId);
            byId.put(ownerId, new OwnerRow(ownerId, login, ids));
          } else {
            existing.repositoryIds().add(repositoryId);
          }
        },
        exactLogin,
        exactLogin);
    List<OwnerRow> rows = new ArrayList<>(byId.size());
    for (OwnerRow row : byId.values()) {
      rows.add(new OwnerRow(row.ownerId(), row.login(), List.copyOf(row.repositoryIds())));
    }
    return List.copyOf(rows);
  }
}
