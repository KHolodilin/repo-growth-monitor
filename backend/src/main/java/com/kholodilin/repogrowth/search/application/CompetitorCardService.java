package com.kholodilin.repogrowth.search.application;

import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubCommunityProfileResponse;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.repository.api.HealthCheckItem;
import com.kholodilin.repogrowth.repository.application.RepositoryHealthEvaluator;
import com.kholodilin.repogrowth.repository.domain.RepositoryHealthFacts;
import com.kholodilin.repogrowth.search.domain.CompetitorProfile;
import com.kholodilin.repogrowth.search.persistence.CompetitorProfileJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchResultJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchRunJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CompetitorCardService {

    private static final Logger log = LoggerFactory.getLogger(CompetitorCardService.class);

    private final SearchQueryService searchQueryService;
    private final SearchResultJdbcRepository resultRepository;
    private final SearchRunJdbcRepository runRepository;
    private final CompetitorProfileJdbcRepository profileRepository;
    private final GitHubClient gitHubClient;
    private final RepositoryHealthEvaluator healthEvaluator;

    public CompetitorCardService(
            SearchQueryService searchQueryService,
            SearchResultJdbcRepository resultRepository,
            SearchRunJdbcRepository runRepository,
            CompetitorProfileJdbcRepository profileRepository,
            GitHubClient gitHubClient,
            RepositoryHealthEvaluator healthEvaluator
    ) {
        this.searchQueryService = searchQueryService;
        this.resultRepository = resultRepository;
        this.runRepository = runRepository;
        this.profileRepository = profileRepository;
        this.gitHubClient = gitHubClient;
        this.healthEvaluator = healthEvaluator;
    }

    public CompetitorCard card(long searchQueryId, long githubRepositoryId) {
        searchQueryService.getQuery(searchQueryId);
        Map<LocalDate, SearchResultJdbcRepository.SnapshotValue> hits = new LinkedHashMap<>();
        String snapshotName = null;
        for (SearchResultJdbcRepository.SnapshotValue row : resultRepository.findSnapshots(searchQueryId)) {
            if (row.githubRepositoryId() != githubRepositoryId) {
                continue;
            }
            hits.put(row.date(), row);
            snapshotName = row.fullName();
        }
        if (snapshotName == null) {
            throw ApiException.notFound("Repository was not found in this search");
        }
        String resolvedName = snapshotName;
        CompetitorProfile profile = profileRepository.find(githubRepositoryId)
                .orElseGet(() -> fetchAndStore(githubRepositoryId, resolvedName));
        List<CompetitorPoint> points = new ArrayList<>();
        for (LocalDate date : runRepository.snapshotDates(searchQueryId)) {
            SearchResultJdbcRepository.SnapshotValue hit = hits.get(date);
            points.add(hit == null
                    ? new CompetitorPoint(date, null, null)
                    : new CompetitorPoint(date, hit.stars(), hit.forks()));
        }
        RepositoryHealthFacts facts = new RepositoryHealthFacts(
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
                profile.hasSocialPreview()
        );
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
                        facts
                ),
                points
        );
    }

    private CompetitorProfile fetchAndStore(long githubRepositoryId, String snapshotName) {
        String[] parts = ownerAndName(snapshotName);
        String owner = parts[0];
        String name = parts[1];
        GitHubRepositoryResponse remote = gitHubClient.getRepository(owner, name);
        String readme = readme(owner, name);
        GitHubCommunityProfileResponse.GitHubCommunityFiles files = communityFiles(owner, name);
        String description = blankToNull(remote.description());
        String fullName = blankToNull(remote.fullName()) == null ? snapshotName : remote.fullName().trim();
        String htmlUrl = blankToNull(remote.htmlUrl());
        CompetitorProfile profile = new CompetitorProfile(
                githubRepositoryId,
                fullName,
                description,
                htmlUrl == null ? "https://github.com/" + fullName : htmlUrl,
                remote.topicsOrEmpty(),
                remote.hasHomepage() ? remote.homepage().trim() : null,
                remote.archived(),
                remote.pushedAt(),
                releaseAt(owner, name),
                files.hasReadme() || readme != null,
                RepositoryHealthEvaluator.readmeHasH1(readme),
                RepositoryHealthEvaluator.readmeHasName(readme, name),
                files.hasLicense() || remote.hasLicense(),
                socialPreview(owner, name),
                Instant.now()
        );
        profileRepository.save(profile);
        return profile;
    }

    private String readme(String owner, String name) {
        try {
            return gitHubClient.getReadme(owner, name).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("README lookup failed owner={} name={} error={}", owner, name, ex.getMessage());
            return null;
        }
    }

    private GitHubCommunityProfileResponse.GitHubCommunityFiles communityFiles(String owner, String name) {
        try {
            return gitHubClient.getCommunityProfile(owner, name).filesOrEmpty();
        } catch (RuntimeException ex) {
            log.warn("Community profile lookup failed owner={} name={} error={}", owner, name, ex.getMessage());
            return GitHubCommunityProfileResponse.GitHubCommunityFiles.empty();
        }
    }

    private boolean socialPreview(String owner, String name) {
        try {
            return gitHubClient.getSocialPreview(owner, name).custom();
        } catch (RuntimeException ex) {
            log.warn("Social preview lookup failed owner={} name={} error={}", owner, name, ex.getMessage());
            return false;
        }
    }

    private Instant releaseAt(String owner, String name) {
        try {
            return gitHubClient.latestReleaseAt(owner, name).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Latest release lookup failed owner={} name={} error={}", owner, name, ex.getMessage());
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    static String[] ownerAndName(String fullName) {
        int slash = fullName == null ? -1 : fullName.indexOf('/');
        if (slash <= 0 || slash == fullName.length() - 1) {
            throw ApiException.notFound("Repository was not found in this search");
        }
        return new String[] {fullName.substring(0, slash), fullName.substring(slash + 1)};
    }

    public record CompetitorPoint(LocalDate date, Integer stars, Integer forks) {
    }

    public record CompetitorCard(
            long githubRepositoryId,
            String fullName,
            String description,
            String htmlUrl,
            List<String> topics,
            List<HealthCheckItem> discoverability,
            List<CompetitorPoint> points
    ) {
    }
}
