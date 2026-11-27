package com.kholodilin.repogrowth.catalog.service;

import com.kholodilin.repogrowth.administration.configuration.PublicRoutes;
import com.kholodilin.repogrowth.administration.configuration.PublicationProperties;
import com.kholodilin.repogrowth.catalog.repository.OwnerDirectoryReadModel;
import com.kholodilin.repogrowth.catalog.repository.PublicHtmlJdbcRepository;
import com.kholodilin.repogrowth.common.api.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Catalog owner-directory reads for the resource API. */
@Service
public class OwnerDirectoryService {

  public record OwnerRow(long ownerId, String login, List<Long> repositoryIds) {}

  public record DirectoryPage(List<Map<String, Object>> items, String nextCursor) {}

  private final OwnerDirectoryReadModel readModel;
  private final PublicHtmlJdbcRepository htmlReads;
  private final PublicationProperties properties;

  public OwnerDirectoryService(
      OwnerDirectoryReadModel readModel,
      PublicHtmlJdbcRepository htmlReads,
      PublicationProperties properties) {
    this.readModel = readModel;
    this.htmlReads = htmlReads;
    this.properties = properties;
  }

  public List<OwnerRow> list(String exactLogin) {
    return readModel.list(exactLogin).stream()
        .map(row -> new OwnerRow(row.ownerId(), row.login(), row.repositoryIds()))
        .toList();
  }

  /** Published owners directory with sort/offset for the landing page. */
  public DirectoryPage directory(String sort, String order, long offset, int size) {
    String column =
        Map.of(
                "owner",
                "login",
                "repositories",
                "repositories",
                "stars",
                "stars",
                "forks",
                "forks",
                "watchers",
                "watchers",
                "contributors",
                "contributors")
            .get(sort);
    if (column == null || !Set.of("asc", "desc").contains(order) || offset < 0 || offset > 10000) {
      throw ApiException.validation("Invalid directory pagination or sort");
    }
    if (size < 1 || size > 100) {
      throw ApiException.validation("limit must be 1..100");
    }
    var rows =
        htmlReads.ownersDirectory(
            column,
            order,
            offset,
            size + 1,
            properties.verificationMaxAge().toMillis(),
            PublicRoutes.RESERVED);
    boolean more = rows.size() > size;
    List<Map<String, Object>> page = more ? rows.subList(0, size) : rows;
    List<Map<String, Object>> safe = page.stream().map(this::stringifyIds).toList();
    return new DirectoryPage(safe, more ? Long.toString(offset + size) : null);
  }

  private Map<String, Object> stringifyIds(Map<String, Object> row) {
    Map<String, Object> copy = new LinkedHashMap<>(row);
    Object ownerId = copy.get("ownerId");
    if (ownerId != null) {
      copy.put("ownerId", ownerId.toString());
    }
    return copy;
  }
}
