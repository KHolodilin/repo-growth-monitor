package com.kholodilin.repogrowth.collection.collector;

import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.event.detect.CandidateEvent;
import com.kholodilin.repogrowth.event.detect.GitHubActivitySnapshot;
import com.kholodilin.repogrowth.event.detect.GrowthEventDetections;
import com.kholodilin.repogrowth.event.domain.GrowthEventCatalog;
import com.kholodilin.repogrowth.event.domain.GrowthEventSetting;
import com.kholodilin.repogrowth.event.domain.GrowthEventState;
import com.kholodilin.repogrowth.event.persistence.GrowthEventJdbcRepository;
import com.kholodilin.repogrowth.event.persistence.GrowthEventSettingJdbcRepository;
import com.kholodilin.repogrowth.event.persistence.GrowthEventStateJdbcRepository;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import com.kholodilin.repogrowth.github.model.GitHubReadmeResponse;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.github.model.GitHubSocialPreview;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Component
public class GrowthEventsCollector implements Collector {

    private final GitHubClient gitHubClient;
    private final GrowthEventJdbcRepository eventRepository;
    private final GrowthEventSettingJdbcRepository settingRepository;
    private final GrowthEventStateJdbcRepository stateRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public GrowthEventsCollector(
            GitHubClient gitHubClient,
            GrowthEventJdbcRepository eventRepository,
            GrowthEventSettingJdbcRepository settingRepository,
            GrowthEventStateJdbcRepository stateRepository,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        this.gitHubClient = gitHubClient;
        this.eventRepository = eventRepository;
        this.settingRepository = settingRepository;
        this.stateRepository = stateRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Override
    public CollectionJobType type() {
        return CollectionJobType.GROWTH_EVENTS;
    }

    @Override
    public void collect(CollectionContext context) {
        String owner = context.ownerLogin();
        String name = context.repository().name();
        GitHubRepositoryResponse remote = readGitHub("Could not load the repository", () -> gitHubClient.getRepository(owner, name));
        GitHubReadmeResponse readme = readGitHub("Could not load the readme", () -> gitHubClient.getReadmeDetails(owner, name).orElse(null));
        Instant readmeCommittedAt = readme == null || readme.name() == null || readme.name().isBlank()
                ? null
                : readGitHub(
                        "Could not load the readme commit",
                        () -> gitHubClient.latestCommitAt(owner, name, readme.name()).orElse(null)
                );
        Boolean customPreview = null;
        String previewUrl = null;
        try {
            GitHubSocialPreview preview = gitHubClient.getSocialPreview(owner, name);
            customPreview = preview.custom();
            previewUrl = preview.imageUrl();
        } catch (RuntimeException ex) {
            log.warn("Social preview lookup failed repository={} error={}", context.repository().fullName(), ex.getMessage());
        }
        GitHubActivitySnapshot snapshot = new GitHubActivitySnapshot(
                remote,
                readme == null ? null : readme.decodedText(),
                readme == null ? null : readme.sha(),
                readmeCommittedAt,
                readGitHub("Could not load issues", () -> gitHubClient.listIssues(owner, name)),
                readGitHub("Could not load pull requests", () -> gitHubClient.listPulls(owner, name)),
                readGitHub("Could not load releases", () -> gitHubClient.listReleases(owner, name)),
                readGitHub("Could not load contributors", () -> gitHubClient.listContributors(owner, name)),
                customPreview,
                previewUrl
        );
        Instant collectedAt = Instant.now(clock);
        transactionTemplate.executeWithoutResult(status -> {
            List<GrowthEventSetting> settings = settingRepository.ensureDefaults(context.repository().id());
            Map<String, Boolean> enabled = settings.stream()
                    .collect(Collectors.toMap(GrowthEventSetting::eventType, GrowthEventSetting::enabled));
            GrowthEventState previous = stateRepository.find(context.repository().id());
            List<CandidateEvent> candidates = GrowthEventDetections.detect(previous, snapshot, owner, collectedAt);
            for (CandidateEvent candidate : candidates) {
                if (!Boolean.TRUE.equals(enabled.get(candidate.type()))) {
                    continue;
                }
                eventRepository.insertIgnore(context.repository().id(), candidate);
            }
            if (readmeCommittedAt != null) {
                eventRepository.pullEventAtBack(
                        context.repository().id(),
                        GrowthEventCatalog.README_SIGNIFICANTLY_CHANGED,
                        readmeCommittedAt
                );
            }
            if (remote.updatedAt() != null) {
                eventRepository.pullEventAtBack(
                        context.repository().id(),
                        GrowthEventCatalog.DESCRIPTION_CHANGED,
                        remote.updatedAt()
                );
                eventRepository.pullEventAtBack(
                        context.repository().id(),
                        GrowthEventCatalog.TOPICS_CHANGED,
                        remote.updatedAt()
                );
                eventRepository.pullEventAtBack(
                        context.repository().id(),
                        GrowthEventCatalog.HOMEPAGE_CHANGED,
                        remote.updatedAt()
                );
            }
            stateRepository.upsert(
                    context.repository().id(),
                    GrowthEventDetections.snapshot(previous, snapshot, owner)
            );
        });
    }

    private <T> T readGitHub(String context, Supplier<T> call) {
        try {
            return call.get();
        } catch (GitHubException ex) {
            throw ex.withContext(context);
        }
    }
}
