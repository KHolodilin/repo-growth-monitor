package com.kholodilin.repogrowth.search.controller;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.common.api.ApiSurface;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.search.model.SearchQuery;
import com.kholodilin.repogrowth.search.service.CompetitorCardService;
import com.kholodilin.repogrowth.search.service.SearchKpiService;
import com.kholodilin.repogrowth.search.service.SearchQueryService;
import com.kholodilin.repogrowth.search.service.SearchQueryService.SearchHistory;
import com.kholodilin.repogrowth.search.service.SearchQueryService.SearchRunResults;
import com.kholodilin.repogrowth.search.service.SearchQueryService.SearchSeries;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified Search resource API ({@link ApiSurface#RESOURCE}). Legacy {@code search-visibility}
 * remains until frontend charts switch to include=history / KPI endpoints.
 */
@RestController
@RequestMapping("/api/v1")
public class SearchResourceController {

  private final ResourceAccessPolicy access;
  private final SearchKpiService kpi;
  private final SearchQueryService searchQueries;
  private final CompetitorCardService competitors;

  public SearchResourceController(
      ResourceAccessPolicy access,
      SearchKpiService kpi,
      SearchQueryService searchQueries,
      CompetitorCardService competitors) {
    this.access = access;
    this.kpi = kpi;
    this.searchQueries = searchQueries;
    this.competitors = competitors;
  }

  @GetMapping("/owners/{ownerId}/search/kpi")
  public SearchKpiResponse ownerSearchKpi(
      @PathVariable long ownerId, @RequestParam(defaultValue = "7d") String period) {
    return kpi.ownerKpi(ownerId, period);
  }

  @GetMapping("/repositories/{repositoryId}/search/kpi")
  public SearchKpiResponse repositorySearchKpi(
      @PathVariable long repositoryId, @RequestParam(defaultValue = "7d") String period) {
    return kpi.repositoryKpi(repositoryId, period);
  }

  @GetMapping("/repositories/{repositoryId}/search-queries")
  public List<SearchHistory> listQueries(
      @PathVariable long repositoryId,
      @RequestParam(required = false) String include,
      @RequestParam(defaultValue = "50") int limit) {
    boolean withHistory = include != null && include.toLowerCase().contains("history");
    return kpi.repositoryQueriesWithHistory(repositoryId, withHistory, limit);
  }

  @PostMapping("/repositories/{repositoryId}/search-queries")
  public SearchQuery create(
      @PathVariable long repositoryId,
      @Valid @RequestBody SearchQueryController.SearchQueryRequest request) {
    access.requireAction(
        repositoryId,
        ResourceAccessPolicy.ResourceAction.MUTATE,
        PublicationService.Module.DASHBOARD);
    return searchQueries.create(
        repositoryId, request.name(), request.query(), request.enabled(), request.resultLimit());
  }

  @GetMapping("/search-queries/{queryId}")
  public SearchQuery get(@PathVariable long queryId) {
    SearchQuery query = searchQueries.getQuery(queryId);
    access.requireReadable(query.repositoryId(), PublicationService.Module.DASHBOARD);
    return query;
  }

  @PutMapping("/search-queries/{queryId}")
  public SearchQuery update(
      @PathVariable long queryId, @RequestBody SearchQueryController.SearchQueryRequest request) {
    SearchQuery existing = searchQueries.getQuery(queryId);
    access.requireAction(
        existing.repositoryId(),
        ResourceAccessPolicy.ResourceAction.MUTATE,
        PublicationService.Module.DASHBOARD);
    return searchQueries.update(
        queryId, request.name(), request.query(), request.enabled(), request.resultLimit());
  }

  @DeleteMapping("/search-queries/{queryId}")
  public void delete(@PathVariable long queryId) {
    SearchQuery existing = searchQueries.getQuery(queryId);
    access.requireAction(
        existing.repositoryId(),
        ResourceAccessPolicy.ResourceAction.MUTATE,
        PublicationService.Module.DASHBOARD);
    searchQueries.delete(queryId);
  }

  @GetMapping("/search-queries/{queryId}/history")
  public SearchHistory history(@PathVariable long queryId) {
    SearchQuery query = searchQueries.getQuery(queryId);
    access.requireReadable(query.repositoryId(), PublicationService.Module.DASHBOARD);
    return searchQueries.history(queryId);
  }

  @GetMapping("/search-queries/{queryId}/results")
  public SearchRunResults results(@PathVariable long queryId) {
    SearchQuery query = searchQueries.getQuery(queryId);
    access.requireReadable(query.repositoryId(), PublicationService.Module.DASHBOARD);
    return searchQueries.latestResults(queryId);
  }

  @GetMapping("/search-queries/{queryId}/series")
  public SearchSeries series(@PathVariable long queryId) {
    SearchQuery query = searchQueries.getQuery(queryId);
    access.requireReadable(query.repositoryId(), PublicationService.Module.DASHBOARD);
    return searchQueries.series(queryId);
  }

  @GetMapping("/search-queries/{queryId}/competitors/{githubRepositoryId}")
  public CompetitorCardService.CompetitorCard competitor(
      @PathVariable long queryId, @PathVariable long githubRepositoryId) {
    SearchQuery query = searchQueries.getQuery(queryId);
    access.requireReadable(query.repositoryId(), PublicationService.Module.DASHBOARD);
    return competitors.card(queryId, githubRepositoryId);
  }

  @GetMapping("/search-runs/{runId}/results")
  public SearchRunResults runResults(@PathVariable long runId) {
    SearchRunResults results = searchQueries.results(runId);
    access.requireReadable(results.query().repositoryId(), PublicationService.Module.DASHBOARD);
    return results;
  }
}
