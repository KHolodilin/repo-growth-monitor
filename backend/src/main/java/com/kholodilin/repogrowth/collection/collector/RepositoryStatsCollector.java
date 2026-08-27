package com.kholodilin.repogrowth.collection.collector;

import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RepositoryStatsCollector implements Collector {

    private final GitHubClient gitHubClient;
    private final RepositoryJdbcRepository repositoryJdbcRepository;
    private final TrafficJdbcRepository trafficJdbcRepository;
    private final TransactionTemplate transactionTemplate;

    public RepositoryStatsCollector(
            GitHubClient gitHubClient,
            RepositoryJdbcRepository repositoryJdbcRepository,
            TrafficJdbcRepository trafficJdbcRepository,
            TransactionTemplate transactionTemplate
    ) {
        this.gitHubClient = gitHubClient;
        this.repositoryJdbcRepository = repositoryJdbcRepository;
        this.trafficJdbcRepository = trafficJdbcRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public CollectionJobType type() {
        return CollectionJobType.REPOSITORY_STATS;
    }

    @Override
    public void collect(CollectionContext context) {
        GitHubRepositoryResponse remote = gitHubClient.getRepository(
                context.ownerLogin(),
                context.repository().name()
        );
        transactionTemplate.executeWithoutResult(status -> {
            repositoryJdbcRepository.updateStats(
                    context.repository().id(),
                    remote.stargazersCount(),
                    remote.watchers(),
                    remote.forksCount(),
                    remote.openIssuesCount(),
                    remote.updatedAt()
            );
            trafficJdbcRepository.upsertDailyStats(
                    context.repository().id(),
                    context.job().businessDate(),
                    remote.stargazersCount(),
                    remote.watchers(),
                    remote.forksCount(),
                    remote.openIssuesCount()
            );
        });
    }
}
