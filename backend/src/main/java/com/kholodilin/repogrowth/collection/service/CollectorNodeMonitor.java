package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.repogrowth.collection.configuration.OrchestrationProperties;
import com.kholodilin.repogrowth.collection.configuration.RecoveryProperties;
import com.kholodilin.repogrowth.collection.integration.CollectorNodeClient;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.kholodilin.repogrowth.collection.repository.CollectorNodeMonitorJdbcRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Component
@ConditionalOnProperty(
    name = {
      "rgm.orchestration.enabled",
      "rgm.credentials.enabled",
      "rgm.orchestration.recovery.enabled"
    },
    havingValue = "true")
public class CollectorNodeMonitor {
  private final CollectorNodeMonitorJdbcRepository nodesDb;
  private final TransactionTemplate tx;
  private final OrchestrationProperties nodes;
  private final RecoveryProperties policy;
  private final CollectorNodeClient client;
  private final AssignmentRecovery recovery;
  private final CollectorResultIngestion ingestion;
  private final String owner = "monitor-" + UUID.randomUUID();

  public CollectorNodeMonitor(
      CollectorNodeMonitorJdbcRepository nodesDb,
      PlatformTransactionManager tm,
      OrchestrationProperties nodes,
      RecoveryProperties policy,
      CollectorNodeClient client,
      AssignmentRecovery recovery,
      CollectorResultIngestion ingestion) {
    this.nodesDb = nodesDb;
    this.tx = new TransactionTemplate(tm);
    this.nodes = nodes;
    this.policy = policy;
    this.client = client;
    this.recovery = recovery;
    this.ingestion = ingestion;
  }

  private Timestamp ts(Instant value) {
    return Timestamp.from(value);
  }

  public boolean eligible(String node) {
    return nodesDb.isEligible(node, policy.nodeInterval().multipliedBy(3).toMillis());
  }

  @Scheduled(fixedDelayString = "${rgm.orchestration.recovery.tick-interval:1000}")
  public void tick() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Node HTTP cannot run in a product transaction");
    for (var node : nodes.nodes()) if (node.enabled()) probe(node);
    var attempts = nodesDb.attemptsDue(policy.nodeInterval().toMillis(), policy.batchSize());
    for (var attempt : attempts) reconcile(attempt);
  }

  private void probe(OrchestrationProperties.Node node) {
    Long generation =
        tx.execute(status -> {
          nodesDb.ensureHealthRow(node.key());
          var rows = nodesDb.claimHealthCheck(node.key());
          if (rows.isEmpty()) return null;
          long next = rows.getFirst() + 1;
          nodesDb.lockHealthCheck(
              next,
              owner,
              nodes.connectTimeout().plus(nodes.readTimeout()).plusSeconds(10).toMillis(),
              node.key());
          return next;
        });
    if (generation == null) return;
    boolean up;
    try {
      up = client.ready(node.key(), OrchestrationProperties.endpoint(node));
    } catch (RuntimeException error) {
      up = false;
    }
    nodesDb.recordHealthResult(up, policy.nodeInterval().toMillis(), node.key(), owner, generation);
  }

  private void reconcile(Map<String, Object> attempt) {
    UUID id = (UUID) attempt.get("public_id"), job = (UUID) attempt.get("job_id");
    String node = (String) attempt.get("node_key"), endpoint = (String) attempt.get("endpoint");
    boolean failed = false;
    boolean terminalKnown =
        OrchestrationStates.TERMINAL_COLLECTOR.contains(
            Objects.toString(attempt.get("last_collector_state"), ""));
    if (eligible(node)) {
      try {
        var observed = client.status(node, endpoint, (UUID) attempt.get("collector_job_id"), id);
        if (observed.isPresent()) {
          JobStatus status = observed.get();
          if (!status.jobId().equals(job) || !status.attemptId().equals(id))
            throw new IllegalStateException("Node status identity mismatch");
          remember(status);
          if (status.status() == JobState.SUCCEEDED || status.status() == JobState.FAILED) {
            terminalKnown = true;
            var receipt = nodesDb.resultReceipts(id);
            if (!receipt.isEmpty()) {
              client.confirm(node, endpoint, status.collectorJobId(), receipt.getFirst());
              return;
            }
            var result = client.result(node, endpoint, status.collectorJobId());
            if (result.isPresent()) {
              if (!result.get().collectorJobId().equals(status.collectorJobId())
                  || !result.get().jobId().equals(job)
                  || !result.get().attemptId().equals(id))
                throw new IllegalStateException("Node result identity mismatch");
              ingestion.apply(node, result.get());
              client.confirm(node, endpoint, status.collectorJobId(), result.get().eventId());
            } else nodesDb.markResultUnavailable(id);
            return; // Never recollect a known terminal result because callback recovery failed.
          }
          Instant progress = status.updatedAt();
          if (((Timestamp) attempt.get("deadline_at")).toInstant().isAfter(Instant.now())
              && (status.status() == JobState.ACCEPTED
                  || status.status() == JobState.RETRY
                  || progress.plus(policy.stalledAfter()).isAfter(Instant.now()))) return;
          failed = true;
        } else failed = true;
      } catch (RuntimeException error) {
        failed = true;
      }
    } else failed = true;
    if (!failed) return;
    if (terminalKnown) {
      nodesDb.markCheckedUnavailable(id);
      return;
    }
    nodesDb.incrementStatusFailures(id);
    int failures = nodesDb.statusFailures(id);
    var health = nodesDb.consecutiveFailures(node);
    boolean confirmed =
        failures >= policy.failureThreshold()
            && (eligible(node)
                || (!health.isEmpty() && health.getFirst() >= policy.failureThreshold()));
    if (!confirmed) return;
    nodes.nodes().stream()
        .filter(n ->
            n.enabled()
                && n.pool().equals(attempt.get("pool"))
                && !n.key().equals(node)
                && eligible(n.key()))
        .sorted(Comparator.comparing(OrchestrationProperties.Node::key))
        .findFirst()
        .ifPresent(target -> recovery.reassign(job, id, target.key()));
  }

  private void remember(JobStatus status) {
    tx.executeWithoutResult(transaction -> {
      nodesDb.lockRequestByJob(status.jobId());
      var tasks = nodesDb.lockTask(status.jobId());
      if (tasks.isEmpty() || !status.attemptId().equals(tasks.getFirst().get("current_attempt_id")))
        return;
      var ids = nodesDb.lockAttemptCollectorJob(status.attemptId());
      if (ids.isEmpty()
          || (ids.getFirst() != null && !ids.getFirst().equals(status.collectorJobId())))
        throw new IllegalStateException("Collector job identity changed");
      Instant progress =
          status.updatedAt().isAfter(Instant.now()) ? Instant.now() : status.updatedAt();
      nodesDb.rememberProgress(
          status.collectorJobId(), progress, status.status().name(), status.attemptId());
      nodesDb.acceptReadyTask(status.jobId());
      nodesDb.ackDispatch(status.attemptId());
    });
  }
}
