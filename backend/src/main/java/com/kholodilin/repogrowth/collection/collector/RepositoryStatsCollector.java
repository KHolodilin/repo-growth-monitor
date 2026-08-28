package com.kholodilin.repogrowth.collection.collector;

import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
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
        int contributors = context.repository().contributors();
        try {
            contributors = gitHubClient.countContributors(context.ownerLogin(), context.repository().name());
        } catch (RuntimeException ex) {
            log.warn("Contributor count failed repository={} error={}", context.repository().fullName(), ex.getMessage());
        }
        int resolvedContributors = contributors;
        java.time.Instant lastCommitAt = null;
        try {
            lastCommitAt = gitHubClient.latestCommitAt(context.ownerLogin(), context.repository().name()).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Latest commit lookup failed repository={} error={}", context.repository().fullName(), ex.getMessage());
        }
        java.time.Instant resolvedLastCommitAt = lastCommitAt;
        transactionTemplate.executeWithoutResult(status -> {
            repositoryJdbcRepository.updateStats(
                    context.repository().id(),
                    remote.stargazersCount(),
                    remote.watchers(),
                    remote.forksCount(),
                    remote.openIssuesCount(),
                    resolvedContributors,
                    remote.updatedAt(),
                    remote.pushedAt(),
                    resolvedLastCommitAt,
                    java.time.Instant.now()
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
