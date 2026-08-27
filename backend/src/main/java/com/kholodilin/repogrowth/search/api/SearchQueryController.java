package com.kholodilin.repogrowth.search.api;

import com.kholodilin.repogrowth.search.application.SearchQueryService;
import com.kholodilin.repogrowth.search.domain.SearchQuery;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class SearchQueryController {

    private final SearchQueryService searchQueryService;

    public SearchQueryController(SearchQueryService searchQueryService) {
        this.searchQueryService = searchQueryService;
    }

    @GetMapping("/repositories/{id}/search-queries")
    public List<SearchQuery> list(@PathVariable long id) {
        return searchQueryService.list(id);
    }

    @PostMapping("/repositories/{id}/search-queries")
    public SearchQuery create(@PathVariable long id, @Valid @RequestBody SearchQueryRequest request) {
        return searchQueryService.create(id, request.name(), request.query(), request.enabled(), request.resultLimit());
    }

    @PutMapping("/search-queries/{id}")
    public SearchQuery update(@PathVariable long id, @RequestBody SearchQueryRequest request) {
        return searchQueryService.update(id, request.name(), request.query(), request.enabled(), request.resultLimit());
    }

    @DeleteMapping("/search-queries/{id}")
    public void delete(@PathVariable long id) {
        searchQueryService.delete(id);
    }

    @PostMapping("/search-queries/{id}/run")
    public RunAccepted run(@PathVariable long id) {
        long runId = searchQueryService.runNow(id);
        return new RunAccepted(runId);
    }

    @GetMapping("/search-queries/{id}/history")
    public SearchQueryService.SearchHistory history(@PathVariable long id) {
        return searchQueryService.history(id);
    }

    @GetMapping("/repositories/{id}/search-visibility")
    public List<SearchQueryService.SearchHistory> visibility(@PathVariable long id) {
        return searchQueryService.visibility(id);
    }

    @GetMapping("/search-runs/{id}/results")
    public SearchQueryService.SearchRunResults results(@PathVariable long id) {
        return searchQueryService.results(id);
    }

    public record SearchQueryRequest(
            String name,
            String query,
            Boolean enabled,
            Integer resultLimit
    ) {
    }

    public record RunAccepted(long searchRunId) {
    }
}
