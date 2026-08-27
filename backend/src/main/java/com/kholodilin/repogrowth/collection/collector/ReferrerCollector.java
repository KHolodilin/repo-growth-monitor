package com.kholodilin.repogrowth.collection.collector;

import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubReferrerResponse;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Component
public class ReferrerCollector implements Collector {

    private final GitHubClient gitHubClient;
    private final TrafficJdbcRepository trafficJdbcRepository;
    private final TransactionTemplate transactionTemplate;

    public ReferrerCollector(
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
        return CollectionJobType.REFERRERS;
    }

    @Override
    public void collect(CollectionContext context) {
        List<GitHubReferrerResponse> referrers = gitHubClient.getReferrers(
                context.ownerLogin(),
                context.repository().name()
        );
        Instant snapshotAt = Instant.now();
        transactionTemplate.executeWithoutResult(status -> {
            for (GitHubReferrerResponse referrer : referrers) {
                trafficJdbcRepository.insertReferrers(
                        context.repository().id(),
                        snapshotAt,
                        referrer.referrer(),
                        referrer.count(),
                        referrer.uniques()
                );
            }
        });
    }
}
