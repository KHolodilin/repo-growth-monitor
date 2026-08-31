package com.kholodilin.repogrowth.collection.collector;

import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.event.detect.CandidateEvent;
import com.kholodilin.repogrowth.event.detect.GitHubActivitySnapshot;
import com.kholodilin.repogrowth.event.detect.GrowthEventDetections;
import com.kholodilin.repogrowth.event.domain.GrowthEventSetting;
import com.kholodilin.repogrowth.event.domain.GrowthEventState;
import com.kholodilin.repogrowth.event.persistence.GrowthEventJdbcRepository;
import com.kholodilin.repogrowth.event.persistence.GrowthEventSettingJdbcRepository;
import com.kholodilin.repogrowth.event.persistence.GrowthEventStateJdbcRepository;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubReadmeResponse;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        GitHubRepositoryResponse remote = gitHubClient.getRepository(owner, name);
        GitHubReadmeResponse readme = gitHubClient.getReadmeDetails(owner, name).orElse(null);
        GitHubActivitySnapshot snapshot = new GitHubActivitySnapshot(
                remote,
                readme == null ? null : readme.decodedText(),
                readme == null ? null : readme.sha(),
                gitHubClient.listIssues(owner, name),
                gitHubClient.listPulls(owner, name),
                gitHubClient.listReleases(owner, name),
                gitHubClient.listContributors(owner, name)
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
            stateRepository.upsert(
                    context.repository().id(),
                    GrowthEventDetections.snapshot(previous, snapshot, owner)
            );
        });
    }
}
