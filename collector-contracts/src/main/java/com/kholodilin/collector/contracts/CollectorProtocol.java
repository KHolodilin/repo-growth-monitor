package com.kholodilin.collector.contracts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared wire contract between RGM (product) and Collector.
 *
 * <p>Values here travel over HTTP/JSON only. They are not product DB rows, Spring beans, or places
 * to put GitHub tokens / encryption keys. Bump {@link #VERSION} when the meaning of a field changes
 * in a breaking way.
 */
public final class CollectorProtocol {
  /** Current schema version accepted by both sides. */
  public static final int VERSION = 1;

  private CollectorProtocol() {}

  /**
   * What kind of GitHub work this job asks Collector to do. RGM chooses the type; Collector never
   * invents work on its own.
   */
  public enum TaskType {
    /** Stars, forks, watchers, contributors and related repository facts for one day. */
    STATS,
    /** Views/visitors and clones/unique cloners traffic windows. */
    TRAFFIC,
    /** Top traffic referrers (sources). */
    REFERRERS,
    /** Top popular content paths. */
    POPULAR_PATHS,
    /** Repository activity signals used to detect growth events. */
    GROWTH_EVENTS,
    /** Rank the tracked repository inside a saved search query. */
    SEARCH_QUERY,
    /** Rank the tracked repository inside a topic watch. */
    TOPIC_WATCH,
    /** Discover repositories visible to the credential scope (refresh board). */
    DISCOVER_REPOSITORIES
  }

  /** Lifecycle of one Collector attempt for an assigned job. */
  public enum JobState {
    /** Collector took the job and will run it. */
    ACCEPTED,
    /** Work is in progress. */
    RUNNING,
    /** Temporary failure; Collector will try again later. */
    RETRY,
    /** Finished successfully; a snapshot was produced. */
    SUCCEEDED,
    /** Finished with a terminal failure; no successful snapshot. */
    FAILED,
    /** Cancelled before a terminal result (for example definition deleted). */
    CANCELLED;

    /**
     * Terminal outcomes: no further execution on this attempt. {@link #SUCCEEDED}, {@link #FAILED}
     * and {@link #CANCELLED} only.
     */
    public boolean isTerminal() {
      return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
  }

  /** Stable machine-readable failure reasons on the wire. */
  public enum ErrorCode {
    /** Payload failed validation or required fields are missing. */
    INVALID_REQUEST,
    /** {@code schemaVersion} is not supported by this node. */
    UNSUPPORTED_VERSION,
    /** Same idempotency key was reused with a different body. */
    IDEMPOTENCY_CONFLICT,
    /** Caller failed service / node authentication. */
    AUTHENTICATION_FAILED,
    /** Authenticated, but not allowed to do this action. */
    PERMISSION_DENIED,
    /** GitHub or upstream rate limit blocked the call. */
    RATE_LIMITED,
    /** Repository, query target or related GitHub resource was not found. */
    SOURCE_NOT_FOUND,
    /** Upstream was temporarily unreachable or returned a soft outage. */
    SOURCE_UNAVAILABLE,
    /** Job deadline passed before a terminal result. */
    DEADLINE_EXCEEDED,
    /** Unexpected error inside Collector. */
    INTERNAL_ERROR
  }

  /** Named numeric metrics that may appear in a {@link Snapshot}. */
  public enum Metric {
    STARS,
    FORKS,
    WATCHERS,
    CONTRIBUTORS
  }

  /**
   * GitHub repository this job is about.
   *
   * @param githubId numeric GitHub repository id as a decimal string (not {@code owner/name})
   * @param owner GitHub login of the owner
   * @param name repository name without owner prefix
   */
  public record RepositoryTarget(String githubId, String owner, String name) {
    public RepositoryTarget {
      text(githubId, "githubId");
      text(owner, "owner");
      text(name, "name");
      if (!githubId.matches("[1-9][0-9]*"))
        throw new IllegalArgumentException("githubId must be a positive decimal string");
    }
  }

  /**
   * Frozen search/topic parameters for one job.
   *
   * <p>RGM sends these already resolved. Collector must not look up watches/queries itself. Use
   * {@link #repository()} for repository-scoped tasks that need no search settings.
   *
   * @param query GitHub search query text ({@link TaskType#SEARCH_QUERY})
   * @param topic topic name ({@link TaskType#TOPIC_WATCH})
   * @param language optional language filter for topic watches
   * @param resultLimit how many top results to keep (1..100), or {@code null} when unused
   * @param sort GitHub search sort (for example {@code stars}); blank/null means Best Match
   * @param order {@code asc} or {@code desc} when sort is set
   * @param publicOnly when {@code true}, treat the result as public-scope verified only if all rows
   *     are public
   */
  public record Parameters(
      String query,
      String topic,
      String language,
      Integer resultLimit,
      String sort,
      String order,
      boolean publicOnly) {
    /** Same as the full constructor with {@code publicOnly=false}. */
    public Parameters(
        String query,
        String topic,
        String language,
        Integer resultLimit,
        String sort,
        String order) {
      this(query, topic, language, resultLimit, sort, order, false);
    }

    public Parameters {
      if (resultLimit != null && (resultLimit < 1 || resultLimit > 100))
        throw new IllegalArgumentException("resultLimit must be 1..100");
      if (order != null && !List.of("asc", "desc").contains(order))
        throw new IllegalArgumentException("order must be asc or desc");
    }

    /** Empty parameters for STATS / TRAFFIC / REFERRERS / PATHS / GROWTH_EVENTS / DISCOVER. */
    public static Parameters repository() {
      return new Parameters(null, null, null, null, null, null);
    }
  }

  /**
   * Non-secret handle that tells RGM which vault binding Collector may resolve for this job. The
   * actual GitHub token is never put on {@link JobRequest}; Collector asks for a {@link
   * CredentialLease} later.
   *
   * @param context authorization context UUID issued when the repository was bound to a credential
   * @param credentialRef stable vault ref (for example {@code github-main})
   */
  public record AuthorizationScope(String context, String credentialRef) {
    public AuthorizationScope {
      text(context, "authorization context");
      text(credentialRef, "credentialRef");
    }
  }

  /**
   * RGM → Collector: “please run this concrete attempt”.
   *
   * <p>One {@code attemptId} is one try. Retries get a new attempt while keeping the same logical
   * {@code jobId}.
   *
   * @param schemaVersion must equal {@link #VERSION}
   * @param requestId product collection request that spawned this work
   * @param jobId durable product task / job id
   * @param attemptId this specific attempt (also used as HTTP idempotency key)
   * @param type work kind
   * @param repository target repository
   * @param businessDate calendar day the snapshot belongs to
   * @param configurationRevision hash/revision of the frozen configuration used for this job
   * @param parameters search/topic settings or {@link Parameters#repository()}
   * @param destinationRef where Collector should deliver the result (RGM destination)
   * @param deadlineAt stop trying after this instant
   * @param authorization vault scope for credential resolve; may be {@code null} only for legacy
   *     jobs
   */
  public record JobRequest(
      int schemaVersion,
      UUID requestId,
      UUID jobId,
      UUID attemptId,
      TaskType type,
      RepositoryTarget repository,
      LocalDate businessDate,
      long configurationRevision,
      Parameters parameters,
      String destinationRef,
      Instant deadlineAt,
      AuthorizationScope authorization) {
    /**
     * Transitional constructor for pre-credential jobs. New execution paths should always pass an
     * explicit {@link AuthorizationScope}.
     */
    public JobRequest(
        int schemaVersion,
        UUID requestId,
        UUID jobId,
        UUID attemptId,
        TaskType type,
        RepositoryTarget repository,
        LocalDate businessDate,
        long configurationRevision,
        Parameters parameters,
        String destinationRef,
        Instant deadlineAt) {
      this(
          schemaVersion,
          requestId,
          jobId,
          attemptId,
          type,
          repository,
          businessDate,
          configurationRevision,
          parameters,
          destinationRef,
          deadlineAt,
          null);
    }

    public JobRequest {
      version(schemaVersion);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(jobId);
      Objects.requireNonNull(attemptId);
      Objects.requireNonNull(type);
      Objects.requireNonNull(repository);
      Objects.requireNonNull(businessDate);
      Objects.requireNonNull(parameters);
      text(destinationRef, "destinationRef");
      Objects.requireNonNull(deadlineAt);
      if (configurationRevision < 0)
        throw new IllegalArgumentException("configurationRevision must not be negative");
      if (type == TaskType.SEARCH_QUERY) text(parameters.query(), "query");
      if (type == TaskType.TOPIC_WATCH) text(parameters.topic(), "topic");
    }
  }

  /**
   * Collector → RGM: “this attempt was accepted into the local queue”.
   *
   * @param schemaVersion must equal {@link #VERSION}
   * @param collectorJobId Collector’s own public id for the queued job
   * @param jobId product job id from the request
   * @param attemptId product attempt id from the request
   * @param status always {@link JobState#ACCEPTED} on this message
   * @param acceptedAt when Collector accepted the attempt
   */
  public record JobAccepted(
      int schemaVersion,
      UUID collectorJobId,
      UUID jobId,
      UUID attemptId,
      JobState status,
      Instant acceptedAt) {
    public JobAccepted {
      version(schemaVersion);
      Objects.requireNonNull(collectorJobId);
      Objects.requireNonNull(jobId);
      Objects.requireNonNull(attemptId);
      Objects.requireNonNull(acceptedAt);
      if (status != JobState.ACCEPTED)
        throw new IllegalArgumentException("acceptance status must be ACCEPTED");
    }
  }

  /**
   * Structured failure carried on status/result messages.
   *
   * @param code stable error code
   * @param retryable whether another attempt may help
   * @param message short human-readable detail (no secrets)
   * @param retryAt earliest suggested retry instant, or {@code null}
   */
  public record Failure(ErrorCode code, boolean retryable, String message, Instant retryAt) {
    public Failure {
      Objects.requireNonNull(code);
      text(message, "message");
    }
  }

  /**
   * Current state of an attempt, for polling / diagnostics.
   *
   * @param schemaVersion must equal {@link #VERSION}
   * @param collectorJobId Collector’s public job id
   * @param jobId product job id
   * @param attemptId product attempt id
   * @param status current {@link JobState}
   * @param updatedAt last local state change
   * @param resultId public result id when a terminal result exists; otherwise {@code null}
   * @param failure present on failed states when known
   */
  public record JobStatus(
      int schemaVersion,
      UUID collectorJobId,
      UUID jobId,
      UUID attemptId,
      JobState status,
      Instant updatedAt,
      UUID resultId,
      Failure failure) {
    public JobStatus {
      version(schemaVersion);
      Objects.requireNonNull(collectorJobId);
      Objects.requireNonNull(jobId);
      Objects.requireNonNull(attemptId);
      Objects.requireNonNull(status);
      Objects.requireNonNull(updatedAt);
    }
  }

  /**
   * One calendar day of GitHub traffic counts. A {@code null} count means “not observed”, not zero.
   */
  public record TrafficDay(
      LocalDate date, Long views, Long visitors, Long clones, Long uniqueCloners) {
    public TrafficDay {
      Objects.requireNonNull(date);
    }
  }

  /**
   * One referrer or popular-path row from GitHub traffic.
   *
   * @param key stable identity (referrer site or path)
   * @param title optional display title; may be blank
   * @param views view count for the observation window
   * @param visitors unique visitor count for the observation window
   */
  public record SourceObservation(String key, String title, long views, long visitors) {
    public SourceObservation {
      text(key, "key");
    }
  }

  /**
   * One row from a search/topic ranking list.
   *
   * @param githubId numeric GitHub repository id
   * @param fullName {@code owner/name}
   * @param position 1-based place in the result list
   * @param stars stars at observation time
   * @param forks forks at observation time
   */
  public record RankedRepository(
      String githubId, String fullName, int position, long stars, long forks) {
    public RankedRepository {
      text(githubId, "githubId");
      text(fullName, "fullName");
      if (position < 1) throw new IllegalArgumentException("position must be positive");
    }
  }

  /**
   * A raw activity item Collector observed (issues, releases, etc.). RGM decides later which of
   * these become product growth events.
   */
  public record EventObservation(
      String externalId,
      String type,
      Instant eventAt,
      String title,
      String description,
      String url) {
    public EventObservation {
      text(externalId, "externalId");
      text(type, "type");
      Objects.requireNonNull(eventAt);
      text(title, "title");
    }
  }

  /**
   * Successful observation payload for one attempt.
   *
   * <p>Lists and maps are always non-null (possibly empty). Missing numeric observations stay
   * {@code null} rather than becoming invented zeros. Extra task-specific JSON may sit in {@code
   * metadata}; what is valid there grows with {@link #VERSION}.
   *
   * @param observedAt when Collector finished reading GitHub for this attempt
   * @param complete {@code true} when the snapshot is considered full for this task type
   * @param metrics named counters such as stars/forks
   * @param contributorLogins contributor logins when collected; {@code null} if not part of this
   *     task
   * @param traffic daily traffic rows
   * @param sources referrers or popular paths
   * @param rankings search/topic ranking rows
   * @param totalResults total GitHub search hits when relevant
   * @param trackedPosition 1-based rank of the tracked repository, or {@code null} if not found in
   *     the fetched page
   * @param metadata opaque extras (for example raw search JSON fragments)
   * @param events activity observations for growth-event detection
   */
  public record Snapshot(
      Instant observedAt,
      boolean complete,
      Map<Metric, Long> metrics,
      List<String> contributorLogins,
      List<TrafficDay> traffic,
      List<SourceObservation> sources,
      List<RankedRepository> rankings,
      Long totalResults,
      Integer trackedPosition,
      Map<String, String> metadata,
      List<EventObservation> events) {
    public Snapshot {
      Objects.requireNonNull(observedAt);
      metrics = Map.copyOf(metrics);
      traffic = List.copyOf(traffic);
      sources = List.copyOf(sources);
      rankings = List.copyOf(rankings);
      metadata = Map.copyOf(metadata);
      events = List.copyOf(events);
      contributorLogins = contributorLogins == null ? null : List.copyOf(contributorLogins);
    }
  }

  /**
   * Collector → RGM: terminal result for one attempt. Exactly one of {@link #snapshot} or {@link
   * #failure} is set, matching {@link #status}.
   *
   * @param schemaVersion must equal {@link #VERSION}
   * @param eventId unique delivery event id (idempotent ingest key)
   * @param resultId Collector result id
   * @param collectorJobId Collector job id
   * @param jobId product job id
   * @param attemptId product attempt id
   * @param type task type that produced this result
   * @param repositoryGithubId repository GitHub id as decimal string
   * @param status {@link JobState#SUCCEEDED} or {@link JobState#FAILED}
   * @param snapshot present only on success
   * @param failure present only on failure
   */
  public record ResultDelivery(
      int schemaVersion,
      UUID eventId,
      UUID resultId,
      UUID collectorJobId,
      UUID jobId,
      UUID attemptId,
      TaskType type,
      String repositoryGithubId,
      JobState status,
      Snapshot snapshot,
      Failure failure) {
    public ResultDelivery {
      version(schemaVersion);
      Objects.requireNonNull(eventId);
      Objects.requireNonNull(resultId);
      Objects.requireNonNull(collectorJobId);
      Objects.requireNonNull(jobId);
      Objects.requireNonNull(attemptId);
      Objects.requireNonNull(type);
      text(repositoryGithubId, "repositoryGithubId");
      if (status == JobState.SUCCEEDED) {
        Objects.requireNonNull(snapshot);
        if (failure != null) throw new IllegalArgumentException("successful result has no failure");
      } else if (status == JobState.FAILED) {
        Objects.requireNonNull(failure);
        if (snapshot != null) throw new IllegalArgumentException("failed result has no snapshot");
      } else throw new IllegalArgumentException("result must be terminal SUCCEEDED or FAILED");
    }
  }

  /**
   * Collector → RGM: “give me the GitHub token for this assigned attempt”. Body carries only
   * job/attempt ids; RGM looks up the frozen authorization scope itself.
   */
  public record CredentialRequest(int schemaVersion, UUID jobId, UUID attemptId) {
    public CredentialRequest {
      version(schemaVersion);
      Objects.requireNonNull(jobId);
      Objects.requireNonNull(attemptId);
    }
  }

  /**
   * Short-lived GitHub token lease returned by RGM.
   *
   * <p>Never persist or log the token. Cache entries must be keyed by context + credential ref +
   * version so rotation or rebinding cannot reuse the wrong secret.
   *
   * @param schemaVersion must equal {@link #VERSION}
   * @param authorizationContext binding context from {@link AuthorizationScope}
   * @param credentialRef vault ref
   * @param credentialVersion current credential version string
   * @param token GitHub token (secret)
   * @param expiresAt hard token expiry, or {@code null} if unknown
   * @param cacheValidUntil how long Collector may keep this lease in memory
   * @param quotaIdentity identity used for shared GitHub rate-limit budgeting
   */
  public record CredentialLease(
      int schemaVersion,
      String authorizationContext,
      String credentialRef,
      String credentialVersion,
      String token,
      Instant expiresAt,
      Instant cacheValidUntil,
      String quotaIdentity) {
    /** Same as the full constructor with an unclassified quota identity. */
    public CredentialLease(
        int schemaVersion,
        String authorizationContext,
        String credentialRef,
        String credentialVersion,
        String token,
        Instant expiresAt,
        Instant cacheValidUntil) {
      this(
          schemaVersion,
          authorizationContext,
          credentialRef,
          credentialVersion,
          token,
          expiresAt,
          cacheValidUntil,
          "github:unclassified");
    }

    public CredentialLease {
      quotaIdentity = quotaIdentity == null ? "github:unclassified" : quotaIdentity;
      version(schemaVersion);
      text(authorizationContext, "authorizationContext");
      text(credentialRef, "credentialRef");
      text(credentialVersion, "credentialVersion");
      text(token, "token");
      Objects.requireNonNull(cacheValidUntil);
      if (expiresAt != null && cacheValidUntil.isAfter(expiresAt))
        throw new IllegalArgumentException("cache lease exceeds token expiry");
    }

    /** Hides the token from logs and debugger toString dumps. */
    @Override
    public String toString() {
      return "CredentialLease[REDACTED]";
    }
  }

  private static void version(int value) {
    if (value != VERSION) throw new IllegalArgumentException("Unsupported schemaVersion: " + value);
  }

  private static void text(String value, String name) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(name + " must not be blank");
  }
}
