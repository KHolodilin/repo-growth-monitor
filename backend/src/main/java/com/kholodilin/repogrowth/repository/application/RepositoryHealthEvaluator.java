package com.kholodilin.repogrowth.repository.application;

import com.kholodilin.repogrowth.repository.api.HealthCheckItem;
import com.kholodilin.repogrowth.repository.api.RepositoryHealthResponse;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.domain.RepositoryHealthFacts;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
        List<HealthCheckItem> discoverability = new ArrayList<>(baseDiscoverability(
                description,
                topics.size(),
                repository.archived(),
                repository.lastCommitAt(),
                repository.lastReleaseAt(),
                facts
        ));
        discoverability.add(new HealthCheckItem("Search Queries configured", searchQueryCount > 0));
        List<HealthCheckItem> community = List.of(
                new HealthCheckItem("Code of Conduct", facts.hasCodeOfConduct()),
                new HealthCheckItem("Contributing guide", facts.hasContributing()),
                new HealthCheckItem("Security policy", facts.hasSecurityPolicy()),
                new HealthCheckItem("Issue templates", facts.hasIssueTemplate()),
                new HealthCheckItem("Pull request template", facts.hasPullRequestTemplate())
        );
        return new RepositoryHealthResponse(List.copyOf(discoverability), community);
    }

    /** Discoverability for a repository we do not monitor. Search queries are our setting, not theirs. */
    public List<HealthCheckItem> competitorDiscoverability(
            String description,
            int topicCount,
            boolean archived,
            Instant lastCommitAt,
            Instant lastReleaseAt,
            RepositoryHealthFacts facts
    ) {
        String text = description == null ? "" : description.trim();
        return baseDiscoverability(text, topicCount, archived, lastCommitAt, lastReleaseAt, facts);
    }

    private List<HealthCheckItem> baseDiscoverability(
            String description,
            int topicCount,
            boolean archived,
            Instant lastCommitAt,
            Instant lastReleaseAt,
            RepositoryHealthFacts facts
    ) {
        Instant commitCutoff = Instant.now(clock).minus(RECENT_COMMIT);
        boolean recentCommit = lastCommitAt != null && !lastCommitAt.isBefore(commitCutoff);
        return List.of(
                new HealthCheckItem("Repository description exists", !description.isEmpty()),
                new HealthCheckItem("Description length >= 50", description.length() >= MIN_DESCRIPTION_LENGTH),
                new HealthCheckItem("Topics configured >= 3", topicCount >= MIN_TOPICS),
                new HealthCheckItem("README exists", facts.hasReadme()),
                new HealthCheckItem("README has H1", facts.readmeHasH1()),
                new HealthCheckItem("README contains repository name / target terms", facts.readmeHasName()),
                new HealthCheckItem("Homepage URL configured", facts.homepage() != null && !facts.homepage().isBlank()),
                new HealthCheckItem("Social preview configured", facts.hasSocialPreview()),
                new HealthCheckItem("License exists", facts.hasLicense()),
                new HealthCheckItem("Repository not archived", !archived),
                new HealthCheckItem("Recent commit <= 90 days", recentCommit),
                new HealthCheckItem("At least 1 release", lastReleaseAt != null)
        );
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
