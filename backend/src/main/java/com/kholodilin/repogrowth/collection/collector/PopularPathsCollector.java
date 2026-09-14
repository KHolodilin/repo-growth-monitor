package com.kholodilin.repogrowth.collection.collector;

import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubPathResponse;
import com.kholodilin.repogrowth.traffic.ServicePathClassifier;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Component
public class PopularPathsCollector implements Collector {

    private final GitHubClient gitHubClient;
    private final TrafficJdbcRepository trafficJdbcRepository;
    private final ServicePathClassifier servicePathClassifier;
    private final TransactionTemplate transactionTemplate;

    public PopularPathsCollector(
            GitHubClient gitHubClient,
            TrafficJdbcRepository trafficJdbcRepository,
            ServicePathClassifier servicePathClassifier,
            TransactionTemplate transactionTemplate
    ) {
        this.gitHubClient = gitHubClient;
        this.trafficJdbcRepository = trafficJdbcRepository;
        this.servicePathClassifier = servicePathClassifier;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public CollectionJobType type() {
        return CollectionJobType.POPULAR_PATHS;
    }

    @Override
    public void collect(CollectionContext context) {
        List<GitHubPathResponse> paths = gitHubClient.getPopularPaths(
                context.ownerLogin(),
                context.repository().name()
        );
        Instant snapshotAt = Instant.now();
        LocalDate snapshotDate = context.job().businessDate();
        long repositoryId = context.repository().id();
        transactionTemplate.executeWithoutResult(status -> {
            // Clearing the day rather than upserting, otherwise paths that dropped out of the
            // GitHub top ten would linger with their stale numbers.
            trafficJdbcRepository.deletePathSnapshot(repositoryId, snapshotDate);
            for (GitHubPathResponse path : paths) {
                trafficJdbcRepository.insertPath(
                        repositoryId,
                        snapshotDate,
                        snapshotAt,
                        path.path(),
                        path.title(),
                        path.count(),
                        path.uniques(),
                        servicePathClassifier.isServicePath(path.path())
                );
            }
        });
    }
}
