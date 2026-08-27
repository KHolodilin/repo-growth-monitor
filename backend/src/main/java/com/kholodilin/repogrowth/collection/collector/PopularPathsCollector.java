package com.kholodilin.repogrowth.collection.collector;

import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubPathResponse;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Component
public class PopularPathsCollector implements Collector {

    private final GitHubClient gitHubClient;
    private final TrafficJdbcRepository trafficJdbcRepository;
    private final TransactionTemplate transactionTemplate;

    public PopularPathsCollector(
            GitHubClient gitHubClient,
            TrafficJdbcRepository trafficJdbcRepository,
            TransactionTemplate transactionTemplate
    ) {
        this.gitHubClient = gitHubClient;
        this.trafficJdbcRepository = trafficJdbcRepository;
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
        transactionTemplate.executeWithoutResult(status -> {
            for (GitHubPathResponse path : paths) {
                trafficJdbcRepository.insertPath(
                        context.repository().id(),
                        snapshotAt,
                        path.path(),
                        path.title(),
                        path.count(),
                        path.uniques()
                );
            }
        });
    }
}
