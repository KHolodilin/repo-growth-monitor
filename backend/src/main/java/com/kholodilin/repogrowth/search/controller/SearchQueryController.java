package com.kholodilin.repogrowth.search.controller;

import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.search.model.SearchQuery;
import com.kholodilin.repogrowth.search.service.CompetitorCardService;
import com.kholodilin.repogrowth.search.service.SearchQueryService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class SearchQueryController {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.kholodilin.repogrowth.orchestration.CollectionRequestService commands;

  @org.springframework.beans.factory.annotation.Autowired
  private com.kholodilin.repogrowth.collection.planner.PlanningWindow commandWindow;

  private final SearchQueryService searchQueryService;
  private final CompetitorCardService competitorCardService;

  public SearchQueryController(
      SearchQueryService searchQueryService, CompetitorCardService competitorCardService) {
    this.searchQueryService = searchQueryService;
    this.competitorCardService = competitorCardService;
  }

  @GetMapping("/repositories/{id}/search-queries")
  public List<SearchQuery> list(@PathVariable long id) {
    return searchQueryService.list(id);
  }

  @org.springframework.web.bind.annotation.ResponseStatus(
      org.springframework.http.HttpStatus.CREATED)
  @PostMapping("/repositories/{id}/search-queries")
  public SearchQuery create(@PathVariable long id, @Valid @RequestBody SearchQueryRequest request) {
    return searchQueryService.create(
        id, request.name(), request.query(), request.enabled(), request.resultLimit());
  }

  @PutMapping("/search-queries/{id}")
  public SearchQuery update(@PathVariable long id, @RequestBody SearchQueryRequest request) {
    return searchQueryService.update(
        id, request.name(), request.query(), request.enabled(), request.resultLimit());
  }

  @DeleteMapping("/search-queries/{id}")
  public void delete(@PathVariable long id) {
    searchQueryService.delete(id);
  }

  @org.springframework.web.bind.annotation.ResponseStatus(
      org.springframework.http.HttpStatus.CREATED)
  @PostMapping("/search-queries/{id}/run")
  public Object run(@PathVariable long id) {
    return requireCommands().searchCommand(id, commandWindow.businessDate());
  }

  @org.springframework.web.bind.annotation.ResponseStatus(
      org.springframework.http.HttpStatus.CREATED)
  @PostMapping("/repositories/{id}/search-queries/run")
  public Object runAll(@PathVariable long id) {
    return requireCommands().searchAllCommand(id, commandWindow.businessDate());
  }

  private com.kholodilin.repogrowth.orchestration.CollectionRequestService requireCommands() {
    if (commands == null) {
      throw ApiException.serviceUnavailable("Collector orchestration is required");
    }
    return commands;
  }

  @GetMapping("/search-queries/{id}")
  public SearchQuery get(@PathVariable long id) {
    return searchQueryService.getQuery(id);
  }

  @GetMapping("/search-queries/{id}/history")
  public SearchQueryService.SearchHistory history(@PathVariable long id) {
    return searchQueryService.history(id);
  }

  @GetMapping("/repositories/{id}/search-visibility")
  public List<SearchQueryService.SearchHistory> visibility(@PathVariable long id) {
    return searchQueryService.visibility(id);
  }

  @GetMapping("/search-queries/{id}/results")
  public SearchQueryService.SearchRunResults latestResults(@PathVariable long id) {
    return searchQueryService.latestResults(id);
  }

  @GetMapping("/search-queries/{id}/series")
  public SearchQueryService.SearchSeries series(@PathVariable long id) {
    return searchQueryService.series(id);
  }

  @GetMapping("/search-queries/{id}/competitors/{githubRepositoryId}")
  public CompetitorCardService.CompetitorCard competitor(
      @PathVariable long id, @PathVariable long githubRepositoryId) {
    return competitorCardService.card(id, githubRepositoryId);
  }

  @GetMapping("/search-runs/{id}/results")
  public SearchQueryService.SearchRunResults results(@PathVariable long id) {
    return searchQueryService.results(id);
  }

  public record SearchQueryRequest(
      String name, String query, Boolean enabled, Integer resultLimit) {}

  public record RunAccepted(long searchRunId) {}

  public record RunAllAccepted(List<Long> searchRunIds) {}
}
