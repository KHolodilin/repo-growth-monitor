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
        List<RepositoryRow> repositories
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

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TrafficPoint(LocalDate date, Long views, Long visitors, Long clones) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RepositoryRow(
            long id,
            String fullName,
            long visitors,
            long views,
            long clones,
            int stars,
            Double growthPercent,
            String collectionStatus,
            List<JobStatus> jobs
    ) {
    }

    public record JobStatus(String jobType, String status) {
    }
}
