package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.idempotency.*;
import com.kholodilin.idempotency.exception.IdempotencyConflictException;
import com.kholodilin.idempotency.jackson.CanonicalJsonFingerprintStrategy;
import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.repogrowth.collection.idempotency.ScopedIdempotency;
import com.kholodilin.repogrowth.common.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.kholodilin.repogrowth.collection.repository.CollectorResultIngestionJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(
    name = {"rgm.orchestration.enabled", "rgm.credentials.enabled"},
    havingValue = "true")
public class CollectorResultIngestion {
  public record Applied(UUID eventId, String outcome) {}

  private final CollectorResultIngestionJdbcRepository ingestion;
  private final TransactionTemplate tx;
  private final IdempotencyService idempotency;
  private final CollectorHistoryApplier history;
  private final JsonMapper json;

  public CollectorResultIngestion(
      CollectorResultIngestionJdbcRepository ingestion,
      PlatformTransactionManager tm,
      @Qualifier("rgmIdempotency") IdempotencyService idempotency,
      CollectorHistoryApplier history,
      JsonMapper json) {
    this.ingestion = ingestion;
    this.tx = new TransactionTemplate(tm);
    this.idempotency = idempotency;
    this.history = history;
    this.json = json;
  }

  private IdempotencyConflictException conflict(ResultDelivery result) {
    return new IdempotencyConflictException(
        new IdempotencyKey("APPLY_COLLECTOR_RESULT", result.eventId().toString()),
        "retained-receipt",
        "different-result");
  }

  public Applied apply(String node, ResultDelivery result) {
    if (json.writeValueAsString(result).getBytes(StandardCharsets.UTF_8).length > 5 * 1024 * 1024)
      throw ApiException.validation("Collector result exceeds 5MB");
    return tx.execute(status -> {
      var requests = ingestion.lockRequestByJob(result.jobId());
      if (requests.isEmpty()) throw ApiException.notFound("Assigned task not found");
      var tasks = ingestion.lockTask(result.jobId());
      var task = tasks.getFirst();
      var attempts = ingestion.lockAttempt(result.attemptId(), task.get("id"));
      if (attempts.isEmpty() || !node.equals(attempts.getFirst().get("node_key")))
        throw ApiException.forbidden("Result is not authorized for this assignment");
      var attempt = attempts.getFirst();
      JobRequest job = json.readValue(attempt.get("payload").toString(), JobRequest.class);
      if (!job.jobId().equals(result.jobId()) || job.type() != result.type())
        throw conflict(result);
      String expectedGithubId = job.repository() == null ? null : job.repository().githubId();
      if (!Objects.equals(expectedGithubId, result.repositoryGithubId())) throw conflict(result);
      if (attempt.get("collector_job_id") != null
          && !attempt.get("collector_job_id").equals(result.collectorJobId()))
        throw conflict(result);
      validate(result, job);
      String scoped = ScopedIdempotency.key("node:" + node, result.eventId().toString());
      return idempotency
          .operation("APPLY_COLLECTOR_RESULT")
          .key(scoped)
          .request(result)
          .execute(
              Applied.class, () -> ExecutionResult.success(applyOnce(task, attempt, job, result)))
          .valueOrThrow();
    });
  }

  private Applied applyOnce(
      Map<String, Object> task,
      Map<String, Object> attempt,
      JobRequest job,
      ResultDelivery result) {
    String hash = new CanonicalJsonFingerprintStrategy().calculate(result);
    var receipts =
        ingestion.lockReceipts(result.attemptId(), result.eventId(), result.resultId());
    if (!receipts.isEmpty()) {
      var old = receipts.getFirst();
      if (!hash.equals(old.get("payload_hash"))
          || !result.eventId().equals(old.get("event_id"))
          || !result.attemptId().equals(old.get("attempt_id"))) throw conflict(result);
      return new Applied(result.eventId(), (String) old.get("outcome"));
    }
    boolean current =
        result.attemptId().equals(task.get("current_attempt_id"))
            && OrchestrationStates.OPEN_TASK.contains(task.get("state"));
    String outcome = current ? "APPLIED" : "IGNORED";
    if (current) {
      history.apply(task, job, result);
      String state = result.status().name();
      ingestion.completeTask(state, task.get("id"), result.attemptId());
      ingestion.completeAttempt(state, result.collectorJobId(), result.attemptId());
    }
    ingestion.insertReceipt(
        result.eventId(),
        result.resultId(),
        result.attemptId(),
        task.get("id"),
        attempt.get("node_id"),
        hash,
        outcome);
    ingestion.completeRequestIfIdle(task.get("request_id"));
    return new Applied(result.eventId(), outcome);
  }

  private void validate(ResultDelivery result, JobRequest job) {
    if (result.snapshot() == null) return;
    var snapshot = result.snapshot();
    if (!snapshot.complete())
      throw ApiException.validation(
          "Incomplete observations cannot be applied as a successful result");
    for (Long value : snapshot.metrics().values()) bounded(value);
    for (var day : snapshot.traffic()) {
      bounded(day.views());
      bounded(day.visitors());
      bounded(day.clones());
      bounded(day.uniqueCloners());
    }
    for (var row : snapshot.sources()) {
      bounded(row.views());
      bounded(row.visitors());
    }
    if (snapshot.totalResults() != null) bounded(snapshot.totalResults());
    if (job.type() == TaskType.SEARCH_QUERY || job.type() == TaskType.TOPIC_WATCH) {
      if (snapshot.totalResults() == null
          || snapshot.rankings().size()
              > Objects.requireNonNullElse(job.parameters().resultLimit(), 50))
        throw ApiException.validation("Invalid ranking window");
      int expected = 1;
      Set<String> ids = new HashSet<>();
      for (var row : snapshot.rankings()) {
        if (row.position() != expected++ || !ids.add(row.githubId()))
          throw ApiException.validation("Invalid ranking positions");
        bounded(row.stars());
        bounded(row.forks());
      }
    }
    String repository = snapshot.metadata().get("repository");
    if (job.type() == TaskType.STATS || job.type() == TaskType.GROWTH_EVENTS) {
      if (repository == null) throw ApiException.validation("Repository observation is required");
      var raw = json.readTree(repository);
      for (String field :
          List.of(
              "id",
              "private",
              "fork",
              "archived",
              "stargazers_count",
              "forks_count",
              "open_issues_count"))
        if (!raw.hasNonNull(field))
          throw ApiException.validation("Repository observation is incomplete");
    }
    if (repository != null
        && job.repository() != null
        && !json.readTree(repository).path("id").asText().equals(job.repository().githubId()))
      throw ApiException.validation("Source repository identity mismatch");
  }

  private void bounded(Long value) {
    if (value != null && (value < 0 || value > Integer.MAX_VALUE))
      throw ApiException.validation("Observation is outside supported bounds");
  }
}
