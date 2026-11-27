package com.kholodilin.repogrowth.search.controller;

import com.kholodilin.repogrowth.search.service.SearchQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Batch search-visibility histories still consumed by repository charts. CRUD/run/history live on
 * {@link SearchResourceController} / collection resource controllers — no {@code /admin} prefix.
 */
@RestController
@RequestMapping("/api/v1")
public class SearchQueryController {

  private final SearchQueryService searchQueryService;

  public SearchQueryController(SearchQueryService searchQueryService) {
    this.searchQueryService = searchQueryService;
  }

  @GetMapping("/repositories/{id}/search-visibility")
  public List<SearchQueryService.SearchHistory> visibility(@PathVariable long id) {
    return searchQueryService.visibility(id);
  }

  /** Shared create/update body for {@link SearchResourceController}. */
  public record SearchQueryRequest(
      String name, String query, Boolean enabled, Integer resultLimit) {}
}
