package com.kholodilin.repogrowth.topic.application;

import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.search.application.CompetitorCardService;
import com.kholodilin.repogrowth.search.application.CompetitorCardService.CompetitorCard;
import com.kholodilin.repogrowth.search.application.CompetitorCardService.CompetitorPoint;
import com.kholodilin.repogrowth.topic.persistence.TopicResultJdbcRepository;
import com.kholodilin.repogrowth.topic.persistence.TopicResultJdbcRepository.SnapshotValue;
import com.kholodilin.repogrowth.topic.persistence.TopicRunJdbcRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TopicCompetitorService {

    private final TopicVisibilityService topicVisibilityService;
    private final TopicRunJdbcRepository runRepository;
    private final TopicResultJdbcRepository resultRepository;
    private final CompetitorCardService competitorCardService;

    public TopicCompetitorService(
            TopicVisibilityService topicVisibilityService,
            TopicRunJdbcRepository runRepository,
            TopicResultJdbcRepository resultRepository,
            CompetitorCardService competitorCardService
    ) {
        this.topicVisibilityService = topicVisibilityService;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.competitorCardService = competitorCardService;
    }

    /** Authorization and scope resolution are shared with the topic detail history. */
    public List<RankSeries> series(long repositoryId, String topic, String scope) {
        long watchId = topicVisibilityService.historyByTopic(repositoryId, topic, scope).watch().id();
        Map<Long, List<SnapshotValue>> grouped = new LinkedHashMap<>();
        for (SnapshotValue row : resultRepository.findSnapshots(watchId)) {
            grouped.computeIfAbsent(row.githubRepositoryId(), ignored -> new ArrayList<>()).add(row);
        }
        return grouped.entrySet().stream().map(entry -> new RankSeries(entry.getKey(),
                entry.getValue().getLast().fullName(), entry.getValue().stream()
                .map(row -> new RankPoint(row.date(), row.position())).toList())).toList();
    }

    public record RankPoint(LocalDate date, int position) { }
    public record RankSeries(long githubRepositoryId, String fullName, List<RankPoint> points) { }

    public CompetitorCard card(long repositoryId, String topic, String scope, long githubRepositoryId) {
        long topicWatchId = topicVisibilityService.historyByTopic(repositoryId, topic, scope).watch().id();
        Map<LocalDate, SnapshotValue> hits = new LinkedHashMap<>();
        String snapshotName = null;
        for (SnapshotValue row : resultRepository.findSnapshots(topicWatchId)) {
            if (row.githubRepositoryId() != githubRepositoryId) {
                continue;
            }
            hits.put(row.date(), row);
            snapshotName = row.fullName();
        }
        if (snapshotName == null) {
            throw ApiException.notFound("Repository was not found in this topic");
        }
        List<CompetitorPoint> points = new ArrayList<>();
        for (LocalDate date : runRepository.snapshotDates(topicWatchId)) {
            SnapshotValue hit = hits.get(date);
            points.add(hit == null
                    ? new CompetitorPoint(date, null, null)
                    : new CompetitorPoint(date, hit.stars(), hit.forks()));
        }
        return competitorCardService.assemble(githubRepositoryId, snapshotName, points);
    }
}
