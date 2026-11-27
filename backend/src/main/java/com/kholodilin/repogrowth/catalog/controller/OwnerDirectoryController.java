package com.kholodilin.repogrowth.catalog.controller;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.service.OwnerDirectoryService;
import com.kholodilin.repogrowth.common.api.ApiSurface;
import com.kholodilin.repogrowth.common.model.page.CursorPage;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog owner directory ({@link ApiSurface#RESOURCE}). Exact {@code login} supports F5; {@code
 * sort}/{@code order}/{@code after} serves the published landing directory.
 */
@RestController
@RequestMapping("/api/v1")
public class OwnerDirectoryController {

  public record OwnerCard(String ownerId, String login) {}

  public record DirectoryPage(List<Map<String, Object>> items, String nextCursor) {}

  private final ResourceAccessPolicy access;
  private final OwnerDirectoryService readModel;

  public OwnerDirectoryController(ResourceAccessPolicy access, OwnerDirectoryService readModel) {
    this.access = access;
    this.readModel = readModel;
  }

  @GetMapping("/owners")
  public Object owners(
      @RequestParam(required = false) String login,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "desc") String order,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "20") int limit) {
    if (sort != null && !sort.isBlank() && (login == null || login.isBlank())) {
      OwnerDirectoryService.DirectoryPage page = readModel.directory(sort, order, after, limit);
      return new DirectoryPage(page.items(), page.nextCursor());
    }
    int pageSize = Math.min(Math.max(limit, 1), 100);
    List<OwnerDirectoryService.OwnerRow> rows = readModel.list(login);
    List<OwnerCard> visible = new ArrayList<>();
    for (OwnerDirectoryService.OwnerRow row : rows) {
      List<Long> readable =
          access.filterReadable(row.repositoryIds(), PublicationService.Module.DASHBOARD);
      if (readable.isEmpty()) {
        continue;
      }
      visible.add(new OwnerCard(Long.toString(row.ownerId()), row.login()));
    }
    long afterId = parseCursor(cursor);
    List<OwnerCard> page = new ArrayList<>();
    String next = null;
    for (OwnerCard card : visible) {
      long id = Long.parseLong(card.ownerId());
      if (id <= afterId) {
        continue;
      }
      if (page.size() == pageSize) {
        next = card.ownerId();
        break;
      }
      page.add(card);
    }
    return CursorPage.of(page, next);
  }

  private static long parseCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0L;
    }
    try {
      long value = Long.parseLong(cursor.trim());
      if (value < 0) {
        throw com.kholodilin.repogrowth.common.api.ApiException.validation("Invalid cursor");
      }
      return value;
    } catch (NumberFormatException ex) {
      throw com.kholodilin.repogrowth.common.api.ApiException.validation("Invalid cursor");
    }
  }
}
