package com.kholodilin.repogrowth.repository.application;

import com.kholodilin.repogrowth.repository.api.HealthCheckItem;
import com.kholodilin.repogrowth.repository.api.RepositoryHealthResponse;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.domain.RepositoryHealthFacts;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class RepositoryHealthEvaluator {

    public static final int MIN_DESCRIPTION_LENGTH = 50;
    public static final int MIN_TOPICS = 3;
    public static final Duration RECENT_COMMIT = Duration.ofDays(90);

    private static final Pattern MARKDOWN_H1 = Pattern.compile("(?m)^#\\s+\\S");

    private final Clock clock;

    public RepositoryHealthEvaluator(Clock clock) {
        this.clock = clock;
    }

    public RepositoryHealthResponse evaluate(
            Repository repository,
            List<String> topics,
            int searchQueryCount,
            RepositoryHealthFacts facts
    ) {
        String description = repository.description() == null ? "" : repository.description().trim();
        Instant commitCutoff = Instant.now(clock).minus(RECENT_COMMIT);
        boolean recentCommit = repository.lastCommitAt() != null && !repository.lastCommitAt().isBefore(commitCutoff);
        List<HealthCheckItem> discoverability = List.of(
                new HealthCheckItem("Repository description exists", !description.isEmpty()),
                new HealthCheckItem("Description length >= 50", description.length() >= MIN_DESCRIPTION_LENGTH),
                new HealthCheckItem("Topics configured >= 3", topics.size() >= MIN_TOPICS),
                new HealthCheckItem("README exists", facts.hasReadme()),
                new HealthCheckItem("README has H1", facts.readmeHasH1()),
                new HealthCheckItem("README contains repository name / target terms", facts.readmeHasName()),
                new HealthCheckItem("Homepage URL configured", facts.homepage() != null && !facts.homepage().isBlank()),
                new HealthCheckItem("License exists", facts.hasLicense()),
                new HealthCheckItem("Repository not archived", !repository.archived()),
                new HealthCheckItem("Recent commit <= 90 days", recentCommit),
                new HealthCheckItem("At least 1 release", repository.lastReleaseAt() != null),
                new HealthCheckItem("Search Queries configured", searchQueryCount > 0)
        );
        List<HealthCheckItem> community = List.of(
                new HealthCheckItem("Code of Conduct", facts.hasCodeOfConduct()),
                new HealthCheckItem("Contributing guide", facts.hasContributing()),
                new HealthCheckItem("Security policy", facts.hasSecurityPolicy()),
                new HealthCheckItem("Issue templates", facts.hasIssueTemplate()),
                new HealthCheckItem("Pull request template", facts.hasPullRequestTemplate())
        );
        return new RepositoryHealthResponse(discoverability, community);
    }

    public static boolean readmeHasH1(String text) {
        return text != null && MARKDOWN_H1.matcher(text).find();
    }

    public static boolean readmeHasName(String text, String repositoryName) {
        if (text == null || text.isBlank() || repositoryName == null || repositoryName.isBlank()) {
            return false;
        }
        String haystack = text.toLowerCase();
        String name = repositoryName.toLowerCase();
        return haystack.contains(name) || haystack.contains(name.replace('-', ' '));
    }
}
