package com.kholodilin.repogrowth.analytics.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record DashboardResponse(
        String period,
        LocalDate from,
        LocalDate to,
        Instant lastSyncAt,
        DashboardState state,
        PartialData partialData,
        CollectionWarning collectionWarning,
        ActiveCollection activeCollection,
        DashboardSummary summary,
        List<TrafficPoint> traffic,
        List<RepositoryRow> repositories,
        Highlights highlights
) {

    public enum DashboardState {
        NO_REPOSITORIES,
        FIRST_COLLECTION,
        READY
    }

    public record PartialData(boolean present, String message) {
    }

    public record CollectionWarning(int partialRepositories, String message) {
    }

    public record ActiveCollection(String status, int successfulJobs, int plannedJobs) {
    }

    public record DashboardSummary(
            int repositories,
            MetricValue views,
            MetricValue visitors,
            MetricValue clones,
            StarsMetric stars
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MetricValue(long value, Double growthPercent) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StarsMetric(long total, Long change) {
    }

    public record TrafficLeader(String fullName, long value) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TrafficPoint(
            LocalDate date,
            Long views,
            Long visitors,
            Long clones,
            List<TrafficLeader> viewsLeaders,
            List<TrafficLeader> visitorsLeaders,
            List<TrafficLeader> clonesLeaders,
            int missingRepositories
    ) {
        public TrafficPoint(LocalDate date, Long views, Long visitors, Long clones) {
            this(date, views, visitors, clones, List.of(), List.of(), List.of(), 0);
        }

        public TrafficPoint(
                LocalDate date,
                Long views,
                Long visitors,
                Long clones,
                List<TrafficLeader> viewsLeaders,
                List<TrafficLeader> visitorsLeaders,
                List<TrafficLeader> clonesLeaders
        ) {
            this(date, views, visitors, clones, viewsLeaders, visitorsLeaders, clonesLeaders, 0);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RepositoryRow(
            long id,
            String fullName,
            long visitors,
            long views,
            long clones,
            int stars,
            int forks,
            Double growthPercent,
            String collectionStatus,
            List<JobStatus> jobs,
            boolean archived,
            Instant activityAt,
            String activityStatus,
            Integer discoverabilityPassed,
            Integer discoverabilityTotal,
            boolean accessible,
            boolean trafficPermissionMissing,
            TrafficNotice trafficNotice
    ) {
    }

    public record TrafficNotice(String reason, LocalDate date, LocalDate lastTrafficDate) {
    }

    public record RankHighlight(long repositoryId, String fullName, String queryName, int fromRank, int toRank) {
    }

    public record EventHighlight(int count, long repositoryId, String fullName, List<String> repositoryNames) {
    }

    public record VisitorHighlight(long repositoryId, String fullName, double growthPercent) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Highlights(
            RankHighlight rankRise,
            RankHighlight rankDrop,
            EventHighlight events,
            VisitorHighlight visitors
    ) {
        public static Highlights none() {
            return new Highlights(null, null, null, null);
        }
    }

    public record JobStatus(String jobType, String status, String errorMessage) {
    }
}
