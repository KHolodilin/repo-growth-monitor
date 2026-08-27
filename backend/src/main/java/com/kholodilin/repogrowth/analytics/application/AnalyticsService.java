package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.collection.api.CollectionController;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.repository.application.RepositoryService;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.traffic.domain.TrafficDaily;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Service
public class AnalyticsService {

    private final RepositoryService repositoryService;
    private final TrafficJdbcRepository trafficJdbcRepository;
    private final CollectionRunJdbcRepository runRepository;
    private final CollectionController collectionController;
    private final Clock clock;

    public AnalyticsService(
            RepositoryService repositoryService,
            TrafficJdbcRepository trafficJdbcRepository,
            CollectionRunJdbcRepository runRepository,
            CollectionController collectionController,
            Clock clock
    ) {
        this.repositoryService = repositoryService;
        this.trafficJdbcRepository = trafficJdbcRepository;
        this.runRepository = runRepository;
        this.collectionController = collectionController;
        this.clock = clock;
    }

    public PortfolioSnapshot portfolio(String period) {
        LocalDate from = fromDate(period);
        List<Repository> tracked = repositoryService.tracked();
        TrafficJdbcRepository.TrafficTotals totals = trafficJdbcRepository.portfolioTotals(from);
        long stars = tracked.stream().mapToLong(Repository::stars).sum();
        List<RepositoryMetrics> rows = tracked.stream().map(repository -> {
            TrafficJdbcRepository.TrafficTotals repoTotals = trafficJdbcRepository.totals(repository.id(), from);
            return new RepositoryMetrics(
                    repository.id(),
                    repository.fullName(),
                    repoTotals.uniqueVisitors(),
                    repoTotals.views(),
                    repoTotals.clones(),
                    repository.stars()
            );
        }).toList();
        return new PortfolioSnapshot(
                tracked.size(),
                totals.views(),
                totals.uniqueVisitors(),
                totals.clones(),
                totals.uniqueCloners(),
                stars,
                rows
        );
    }

    public RepositoryTrafficSnapshot traffic(long repositoryId, String period) {
        Repository repository = repositoryService.get(repositoryId);
        GitHubOwner owner = repositoryService.owner(repository.ownerId());
        LocalDate from = fromDate(period);
        List<TrafficDaily> history = trafficJdbcRepository.history(repositoryId, from);
        java.time.Instant referrerSnapshot = trafficJdbcRepository.latestReferrerSnapshot(repositoryId);
        java.time.Instant pathSnapshot = trafficJdbcRepository.latestPathSnapshot(repositoryId);
        CollectionRun latestRun = runRepository.latestForRepository(repositoryId).orElse(null);
        return new RepositoryTrafficSnapshot(
                repository,
                owner,
                history,
                trafficJdbcRepository.referrers(repositoryId, referrerSnapshot),
                trafficJdbcRepository.paths(repositoryId, pathSnapshot),
                latestRun == null ? null : collectionController.toResponse(latestRun)
        );
    }

    private LocalDate fromDate(String period) {
        LocalDate today = LocalDate.now(clock);
        if (period == null || period.isBlank() || "all".equalsIgnoreCase(period)) {
            return null;
        }
        return switch (period.toLowerCase()) {
            case "7d" -> today.minusDays(6);
            case "30d" -> today.minusDays(29);
            case "90d" -> today.minusDays(89);
            case "1y" -> today.minusYears(1).plusDays(1);
            default -> today.minusDays(29);
        };
    }

    public record PortfolioSnapshot(
            int repositories,
            long views,
            long visitors,
            long clones,
            long uniqueCloners,
            long stars,
            List<RepositoryMetrics> table
    ) {
    }

    public record RepositoryMetrics(
            long id,
            String fullName,
            long visitors,
            long views,
            long clones,
            int stars
    ) {
    }

    public record RepositoryTrafficSnapshot(
            Repository repository,
            GitHubOwner owner,
            List<TrafficDaily> history,
            List<TrafficJdbcRepository.ReferrerRow> referrers,
            List<TrafficJdbcRepository.PathRow> paths,
            CollectionController.CollectionRunResponse lastCollection
    ) {
    }
}
