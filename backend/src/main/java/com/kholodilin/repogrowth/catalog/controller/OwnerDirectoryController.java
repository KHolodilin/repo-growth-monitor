package com.kholodilin.repogrowth.catalog.controller;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.auth.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.catalog.repository.OwnerDirectoryReadModel;
import com.kholodilin.repogrowth.common.api.ApiSurface;
import com.kholodilin.repogrowth.common.model.page.CursorPage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 1 skeleton for catalog owner directory ({@link ApiSurface#RESOURCE}). Visibility is
 * filtered before pagination/cursor so hidden owners never inflate counts or nextCursor.
 */
@RestController
@RequestMapping("/api/v1")
public class OwnerDirectoryController {

  public record OwnerCard(String ownerId, String login) {}

  private final ResourceAccessPolicy access;
  private final OwnerDirectoryReadModel readModel;

  public OwnerDirectoryController(ResourceAccessPolicy access, OwnerDirectoryReadModel readModel) {
    this.access = access;
    this.readModel = readModel;
  }

  @GetMapping("/owners")
  public CursorPage<OwnerCard> owners(
      @RequestParam(required = false) String login,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    int pageSize = Math.min(Math.max(limit, 1), 100);
    List<OwnerDirectoryReadModel.OwnerRow> rows = readModel.list(login);
    // Filter by readable repositories belonging to the owner before paging.
    List<OwnerCard> visible = new ArrayList<>();
    for (OwnerDirectoryReadModel.OwnerRow row : rows) {
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
