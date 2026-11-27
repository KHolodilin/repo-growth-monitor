package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.collector.contracts.CollectorProtocol.JobRequest;
import com.kholodilin.repogrowth.collection.configuration.OrchestrationProperties;
import com.kholodilin.repogrowth.collection.repository.AssignmentRecoveryJdbcRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * RGM-owned atomic reassignment. A network failure alone must never call this without policy
 * evaluation.
 */
@Service
@ConditionalOnProperty(name = "rgm.orchestration.enabled", havingValue = "true")
public class AssignmentRecovery {
  public record Reassigned(boolean changed, UUID jobId, UUID attemptId, String reason) {}

  private final AssignmentRecoveryJdbcRepository recovery;
  private final TransactionTemplate tx;
  private final OrchestrationProperties properties;
  private final JsonMapper json;
  private final Duration minimumAge;
  private final int maximumAttempts;

  public AssignmentRecovery(
      AssignmentRecoveryJdbcRepository recovery,
      PlatformTransactionManager tm,
      OrchestrationProperties properties,
      JsonMapper json,
      Environment env) {
    this.recovery = recovery;
    this.tx = new TransactionTemplate(tm);
    this.properties = properties;
    this.json = json;
    minimumAge =
        env.getProperty(
            "rgm.orchestration.recovery.minimum-age", Duration.class, Duration.ofMinutes(2));
    maximumAttempts =
        env.getProperty("rgm.orchestration.recovery.maximum-attempts", Integer.class, 3);
    if (minimumAge.isNegative() || maximumAttempts < 1 || maximumAttempts > 10)
      throw new IllegalArgumentException("Invalid reassignment policy");
  }

  public Reassigned reassign(UUID jobId, UUID expectedAttempt, String targetNode) {
    return tx.execute(status -> {
      recovery.lockRequestByJob(jobId);
      var tasks = recovery.lockActiveTask(jobId);
      if (tasks.isEmpty())
        return new Reassigned(false, jobId, expectedAttempt, OrchestrationStates.REASON_NOT_ACTIVE);
      var task = tasks.getFirst();
      if (!expectedAttempt.equals(task.get("current_attempt_id")))
        return new Reassigned(
            false,
            jobId,
            (UUID) task.get("current_attempt_id"),
            OrchestrationStates.REASON_NOT_CURRENT);
      if (!OrchestrationStates.OPEN_TASK.contains(task.get("state")))
        return new Reassigned(false, jobId, expectedAttempt, OrchestrationStates.REASON_TERMINAL);
      var prior = recovery.lockAttemptDispatch(expectedAttempt);
      if (!OrchestrationStates.ACTIVE_ATTEMPT.contains(prior.get("state")))
        return new Reassigned(false, jobId, expectedAttempt, OrchestrationStates.REASON_NOT_ACTIVE);
      Instant now = recovery.clockTimestamp();
      if (((Timestamp) prior.get("assigned_at")).toInstant().plus(minimumAge).isAfter(now))
        return new Reassigned(false, jobId, expectedAttempt, OrchestrationStates.REASON_TOO_RECENT);
      int number = ((Number) prior.get("attempt_no")).intValue();
      if (number >= maximumAttempts) {
        recovery.failAttempt(expectedAttempt);
        recovery.failTask(task.get("id"));
        recovery.blockDispatch(expectedAttempt, OrchestrationStates.LAST_ERROR_ATTEMPT_LIMIT);
        recovery.failRequestIfIdle(task.get("request_id"));
        return new Reassigned(
            false, jobId, expectedAttempt, OrchestrationStates.REASON_ATTEMPT_LIMIT);
      }
      var target =
          properties.nodes().stream()
              .filter(n ->
                  n.enabled()
                      && n.key().equals(targetNode)
                      && n.pool().equals(prior.get("pool"))
                      && !n.key().equals(prior.get("node_key")))
              .findFirst()
              .orElseThrow(() ->
                  new IllegalArgumentException(
                      "Reassignment requires another configured node in the same pool"));
      Long nodeId =
          recovery.upsertCollectorNode(
              target.key(), target.pool(), OrchestrationProperties.endpoint(target));
      JobRequest original = json.readValue(prior.get("payload").toString(), JobRequest.class);
      UUID next = UUID.randomUUID();
      Instant deadline =
          now.plus(properties.jobTimeout()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      JobRequest replacement =
          new JobRequest(
              original.schemaVersion(),
              original.requestId(),
              original.jobId(),
              next,
              original.type(),
              original.repository(),
              original.businessDate(),
              original.configurationRevision(),
              original.parameters(),
              original.destinationRef(),
              deadline,
              original.authorization());
      recovery.supersedeAttempt(expectedAttempt, now);
      recovery.blockDispatchSuperseded(expectedAttempt, OrchestrationStates.LAST_ERROR_SUPERSEDED);
      recovery.insertAttempt(next, task.get("id"), number + 1, nodeId);
      recovery.setCurrentAttemptReady(next, task.get("id"));
      recovery.insertDispatch(
          next,
          OrchestrationProperties.endpoint(target),
          json.writeValueAsString(replacement),
          deadline);
      return new Reassigned(true, jobId, next, OrchestrationStates.REASON_REASSIGNED);
    });
  }
}
