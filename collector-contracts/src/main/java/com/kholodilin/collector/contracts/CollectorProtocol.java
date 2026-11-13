package com.kholodilin.collector.contracts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Versioned transport-only values. No product persistence, Spring or credential secrets. */
public final class CollectorProtocol {
    public static final int VERSION = 1;
    private CollectorProtocol() { }

    public enum TaskType { STATS, TRAFFIC, REFERRERS, POPULAR_PATHS, GROWTH_EVENTS, SEARCH_QUERY, TOPIC_WATCH, DISCOVER_REPOSITORIES }
    public enum JobState { ACCEPTED, RUNNING, RETRY, SUCCEEDED, FAILED, CANCELLED }
    public enum ErrorCode { INVALID_REQUEST, UNSUPPORTED_VERSION, IDEMPOTENCY_CONFLICT, AUTHENTICATION_FAILED,
        PERMISSION_DENIED, RATE_LIMITED, SOURCE_NOT_FOUND, SOURCE_UNAVAILABLE, DEADLINE_EXCEEDED, INTERNAL_ERROR }
    public enum Metric { STARS, FORKS, WATCHERS, CONTRIBUTORS }

    public record RepositoryTarget(String githubId, String owner, String name) {
        public RepositoryTarget {
            text(githubId, "githubId"); text(owner, "owner"); text(name, "name");
            if (!githubId.matches("[1-9][0-9]*")) throw new IllegalArgumentException("githubId must be a positive decimal string");
        }
    }

    /** Query/watch settings are supplied by RGM, never discovered by a collector scheduler. */
    public record Parameters(String query, String topic, String language, Integer resultLimit, String sort, String order,boolean publicOnly) {
        public Parameters(String query,String topic,String language,Integer resultLimit,String sort,String order){this(query,topic,language,resultLimit,sort,order,false);}
        public Parameters {
            if (resultLimit != null && (resultLimit < 1 || resultLimit > 100)) throw new IllegalArgumentException("resultLimit must be 1..100");
            if (order != null && !List.of("asc", "desc").contains(order)) throw new IllegalArgumentException("order must be asc or desc");
        }
        public static Parameters repository() { return new Parameters(null, null, null, null, null, null); }
    }

    public record AuthorizationScope(String context,String credentialRef) {
        public AuthorizationScope {text(context,"authorization context");text(credentialRef,"credentialRef");}
    }
    public record JobRequest(int schemaVersion, UUID requestId, UUID jobId, UUID attemptId,
            TaskType type, RepositoryTarget repository, LocalDate businessDate, long configurationRevision,
            Parameters parameters, String destinationRef, Instant deadlineAt,AuthorizationScope authorization) {
        /** Transitional constructor for pre-credential jobs. New execution requires an explicit scope. */
        public JobRequest(int schemaVersion,UUID requestId,UUID jobId,UUID attemptId,TaskType type,
                RepositoryTarget repository,LocalDate businessDate,long configurationRevision,
                Parameters parameters,String destinationRef,Instant deadlineAt) {
            this(schemaVersion,requestId,jobId,attemptId,type,repository,businessDate,configurationRevision,parameters,destinationRef,deadlineAt,null);
        }
        public JobRequest {
            version(schemaVersion); Objects.requireNonNull(requestId); Objects.requireNonNull(jobId);
            Objects.requireNonNull(attemptId); Objects.requireNonNull(type); Objects.requireNonNull(repository);
            Objects.requireNonNull(businessDate); Objects.requireNonNull(parameters); text(destinationRef, "destinationRef");
            Objects.requireNonNull(deadlineAt);
            if (configurationRevision < 0) throw new IllegalArgumentException("configurationRevision must not be negative");
            if (type == TaskType.SEARCH_QUERY) text(parameters.query(), "query");
            if (type == TaskType.TOPIC_WATCH) text(parameters.topic(), "topic");
        }
    }

    public record JobAccepted(int schemaVersion, UUID collectorJobId, UUID jobId, UUID attemptId, JobState status, Instant acceptedAt) {
        public JobAccepted {
            version(schemaVersion); Objects.requireNonNull(collectorJobId); Objects.requireNonNull(jobId);
            Objects.requireNonNull(attemptId); Objects.requireNonNull(acceptedAt);
            if (status != JobState.ACCEPTED) throw new IllegalArgumentException("acceptance status must be ACCEPTED");
        }
    }

    public record Failure(ErrorCode code, boolean retryable, String message, Instant retryAt) {
        public Failure { Objects.requireNonNull(code); text(message, "message"); }
    }
    public record JobStatus(int schemaVersion, UUID collectorJobId, UUID jobId, UUID attemptId,
            JobState status, Instant updatedAt, UUID resultId, Failure failure) {
        public JobStatus {
            version(schemaVersion); Objects.requireNonNull(collectorJobId); Objects.requireNonNull(jobId);
            Objects.requireNonNull(attemptId); Objects.requireNonNull(status); Objects.requireNonNull(updatedAt);
        }
    }

    public record TrafficDay(LocalDate date, Long views, Long visitors, Long clones, Long uniqueCloners) {
        public TrafficDay { Objects.requireNonNull(date); }
    }
    public record SourceObservation(String key, String title, long views, long visitors) {
        public SourceObservation { text(key, "key"); }
    }
    public record RankedRepository(String githubId, String fullName, int position, long stars, long forks) {
        public RankedRepository { text(githubId, "githubId"); text(fullName, "fullName"); if (position < 1) throw new IllegalArgumentException("position must be positive"); }
    }
    public record EventObservation(String externalId, String type, Instant eventAt, String title, String description, String url) {
        public EventObservation { text(externalId, "externalId"); text(type, "type"); Objects.requireNonNull(eventAt); text(title, "title"); }
    }

    /** Null/empty means no observation, not an invented zero. Full result capabilities evolve with schemaVersion. */
    public record Snapshot(Instant observedAt, boolean complete, Map<Metric, Long> metrics,
            List<String> contributorLogins, List<TrafficDay> traffic, List<SourceObservation> sources,
            List<RankedRepository> rankings, Long totalResults, Integer trackedPosition,
            Map<String, String> metadata, List<EventObservation> events) {
        public Snapshot {
            Objects.requireNonNull(observedAt); metrics = Map.copyOf(metrics); traffic = List.copyOf(traffic);
            sources = List.copyOf(sources); rankings = List.copyOf(rankings); metadata = Map.copyOf(metadata); events = List.copyOf(events);
            contributorLogins = contributorLogins == null ? null : List.copyOf(contributorLogins);
        }
    }

    public record ResultDelivery(int schemaVersion, UUID eventId, UUID resultId, UUID collectorJobId,
            UUID jobId, UUID attemptId, TaskType type, String repositoryGithubId, JobState status,
            Snapshot snapshot, Failure failure) {
        public ResultDelivery {
            version(schemaVersion); Objects.requireNonNull(eventId); Objects.requireNonNull(resultId);
            Objects.requireNonNull(collectorJobId); Objects.requireNonNull(jobId); Objects.requireNonNull(attemptId);
            Objects.requireNonNull(type); text(repositoryGithubId, "repositoryGithubId");
            if (status == JobState.SUCCEEDED) {
                Objects.requireNonNull(snapshot); if (failure != null) throw new IllegalArgumentException("successful result has no failure");
            } else if (status == JobState.FAILED) {
                Objects.requireNonNull(failure); if (snapshot != null) throw new IllegalArgumentException("failed result has no snapshot");
            } else throw new IllegalArgumentException("result must be terminal SUCCEEDED or FAILED");
        }
    }

    public record CredentialRequest(int schemaVersion, UUID jobId, UUID attemptId) {
        public CredentialRequest { version(schemaVersion); Objects.requireNonNull(jobId); Objects.requireNonNull(attemptId); }
    }
    /** Never persist/log this value. Context/version keys isolate credential cache entries. */
    public record CredentialLease(int schemaVersion, String authorizationContext, String credentialRef,
            String credentialVersion, String token, Instant expiresAt, Instant cacheValidUntil,String quotaIdentity) {
        public CredentialLease(int schemaVersion,String authorizationContext,String credentialRef,String credentialVersion,String token,Instant expiresAt,Instant cacheValidUntil){this(schemaVersion,authorizationContext,credentialRef,credentialVersion,token,expiresAt,cacheValidUntil,"github:unclassified");}
        public CredentialLease {
            quotaIdentity=quotaIdentity==null?"github:unclassified":quotaIdentity;
            version(schemaVersion); text(authorizationContext, "authorizationContext"); text(credentialRef, "credentialRef");
            text(credentialVersion, "credentialVersion"); text(token, "token"); Objects.requireNonNull(cacheValidUntil);
            if (expiresAt != null && cacheValidUntil.isAfter(expiresAt)) throw new IllegalArgumentException("cache lease exceeds token expiry");
        }
        @Override public String toString() { return "CredentialLease[REDACTED]"; }
    }
    private static void version(int value) {
        if (value != VERSION) throw new IllegalArgumentException("Unsupported schemaVersion: " + value);
    }
    private static void text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
