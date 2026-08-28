package com.kholodilin.repogrowth.collection.collector;

import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubCommunityProfileResponse;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.repository.application.RepositoryHealthEvaluator;
import com.kholodilin.repogrowth.repository.domain.RepositoryHealthFacts;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

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
        String owner = context.ownerLogin();
        String name = context.repository().name();
        GitHubRepositoryResponse remote = gitHubClient.getRepository(owner, name);
        int contributors = context.repository().contributors();
        try {
            contributors = gitHubClient.countContributors(owner, name);
        } catch (RuntimeException ex) {
            log.warn("Contributor count failed repository={} error={}", context.repository().fullName(), ex.getMessage());
        }
        int resolvedContributors = contributors;
        Instant lastCommitAt = null;
        try {
            lastCommitAt = gitHubClient.latestCommitAt(owner, name).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Latest commit lookup failed repository={} error={}", context.repository().fullName(), ex.getMessage());
        }
        Instant resolvedLastCommitAt = lastCommitAt;

        GitHubCommunityProfileResponse profile = GitHubCommunityProfileResponse.empty();
        try {
            profile = gitHubClient.getCommunityProfile(owner, name);
        } catch (RuntimeException ex) {
            log.warn("Community profile failed repository={} error={}", context.repository().fullName(), ex.getMessage());
        }
        GitHubCommunityProfileResponse.GitHubCommunityFiles files = profile.filesOrEmpty();

        String readmeText = null;
        try {
            readmeText = gitHubClient.getReadme(owner, name).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("README lookup failed repository={} error={}", context.repository().fullName(), ex.getMessage());
        }
        String resolvedReadme = readmeText;

        boolean hasSecurityPolicy = false;
        try {
            hasSecurityPolicy = gitHubClient.fileExists(owner, name, ".github/SECURITY.md")
                    || gitHubClient.fileExists(owner, name, "SECURITY.md")
                    || gitHubClient.fileExists(owner, name, "docs/SECURITY.md");
        } catch (RuntimeException ex) {
            log.warn("Security policy lookup failed repository={} error={}", context.repository().fullName(), ex.getMessage());
        }
        boolean resolvedSecurityPolicy = hasSecurityPolicy;

        Instant lastReleaseAt = null;
        boolean releaseFetched = false;
        try {
            lastReleaseAt = gitHubClient.latestReleaseAt(owner, name).orElse(null);
            releaseFetched = true;
        } catch (RuntimeException ex) {
            log.warn("Latest release lookup failed repository={} error={}", context.repository().fullName(), ex.getMessage());
        }
        Instant resolvedLastReleaseAt = lastReleaseAt;
        boolean resolvedReleaseFetched = releaseFetched;

        boolean hasReadme = files.hasReadme() || resolvedReadme != null;
        RepositoryHealthFacts healthFacts = new RepositoryHealthFacts(
                remote.hasHomepage() ? remote.homepage().trim() : null,
                hasReadme,
                RepositoryHealthEvaluator.readmeHasH1(resolvedReadme),
                RepositoryHealthEvaluator.readmeHasName(resolvedReadme, name),
                files.hasLicense() || remote.hasLicense(),
                files.hasCodeOfConduct(),
                files.hasContributing(),
                resolvedSecurityPolicy,
                files.hasIssueTemplate(),
                files.hasPullRequestTemplate()
        );

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
                    Instant.now(),
                    remote.archived()
            );
            repositoryJdbcRepository.replaceTopics(context.repository().id(), remote.topicsOrEmpty());
            repositoryJdbcRepository.upsertHealth(context.repository().id(), healthFacts);
            if (resolvedReleaseFetched) {
                repositoryJdbcRepository.updateLastReleaseAt(context.repository().id(), resolvedLastReleaseAt);
            }
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
