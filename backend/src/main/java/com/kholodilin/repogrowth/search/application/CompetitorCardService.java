package com.kholodilin.repogrowth.search.application;

import com.kholodilin.repogrowth.catalog.controller.HealthCheckItem;
import com.kholodilin.repogrowth.catalog.model.RepositoryHealthFacts;
import com.kholodilin.repogrowth.catalog.service.RepositoryHealthEvaluator;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.search.domain.CompetitorProfile;
import com.kholodilin.repogrowth.search.persistence.CompetitorProfileJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchResultJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchRunJdbcRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CompetitorCardService {

  private final SearchQueryService searchQueryService;
  private final SearchResultJdbcRepository resultRepository;
  private final SearchRunJdbcRepository runRepository;
  private final CompetitorProfileJdbcRepository profileRepository;
  private final RepositoryHealthEvaluator healthEvaluator;

  public CompetitorCardService(
      SearchQueryService searchQueryService,
      SearchResultJdbcRepository resultRepository,
      SearchRunJdbcRepository runRepository,
      CompetitorProfileJdbcRepository profileRepository,
      RepositoryHealthEvaluator healthEvaluator) {
    this.searchQueryService = searchQueryService;
    this.resultRepository = resultRepository;
    this.runRepository = runRepository;
    this.profileRepository = profileRepository;
    this.healthEvaluator = healthEvaluator;
  }

  public CompetitorCard card(long searchQueryId, long githubRepositoryId) {
    searchQueryService.getQuery(searchQueryId);
    Map<LocalDate, SearchResultJdbcRepository.SnapshotValue> hits = new LinkedHashMap<>();
    String snapshotName = null;
    for (SearchResultJdbcRepository.SnapshotValue row :
        resultRepository.findSnapshots(searchQueryId)) {
      if (row.githubRepositoryId() != githubRepositoryId) {
        continue;
      }
      hits.put(row.date(), row);
      snapshotName = row.fullName();
    }
    if (snapshotName == null) {
      throw ApiException.notFound("Repository was not found in this search");
    }
    List<CompetitorPoint> points = new ArrayList<>();
    for (LocalDate date : runRepository.snapshotDates(searchQueryId)) {
      SearchResultJdbcRepository.SnapshotValue hit = hits.get(date);
      points.add(
          hit == null
              ? new CompetitorPoint(date, null, null)
              : new CompetitorPoint(date, hit.stars(), hit.forks()));
    }
    return assemble(githubRepositoryId, snapshotName, points);
  }

  public CompetitorCard assemble(
      long githubRepositoryId, String snapshotName, List<CompetitorPoint> points) {
    CompetitorProfile profile =
        profileRepository
            .find(githubRepositoryId)
            .orElseGet(() -> stubProfile(githubRepositoryId, snapshotName));
    RepositoryHealthFacts facts =
        new RepositoryHealthFacts(
            profile.homepage(),
            profile.hasReadme(),
            profile.readmeHasH1(),
            profile.readmeHasName(),
            profile.hasLicense(),
            false,
            false,
            false,
            false,
            false,
            profile.hasSocialPreview());
    return new CompetitorCard(
        githubRepositoryId,
        profile.fullName(),
        profile.description(),
        profile.htmlUrl(),
        profile.topics(),
        healthEvaluator.competitorDiscoverability(
            profile.description(),
            profile.topics().size(),
            profile.archived(),
            profile.lastCommitAt(),
            profile.lastReleaseAt(),
            facts),
        points);
  }

  private static CompetitorProfile stubProfile(long githubRepositoryId, String snapshotName) {
    return new CompetitorProfile(
        githubRepositoryId,
        snapshotName,
        null,
        null,
        List.of(),
        null,
        false,
        null,
        null,
        false,
        false,
        false,
        false,
        false,
        null);
  }

  public record CompetitorPoint(LocalDate date, Integer stars, Integer forks) {}

  public record CompetitorCard(
      long githubRepositoryId,
      String fullName,
      String description,
      String htmlUrl,
      List<String> topics,
      List<HealthCheckItem> discoverability,
      List<CompetitorPoint> points) {}
}
