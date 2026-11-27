package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.collector.contracts.CollectorProtocol.CredentialRequest;
import com.kholodilin.repogrowth.collection.configuration.GitHubBudgetProperties;
import com.kholodilin.repogrowth.collection.repository.GitHubBudgetJdbcRepository;
import com.kholodilin.repogrowth.credentials.service.CredentialVault;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Central, durable quota authority. A node restart never replenishes the shared budget. */
@Service
@ConditionalOnProperty(name = "rgm.credentials.enabled", havingValue = "true")
public class SharedGitHubBudget {
  public record Acquisition(UUID requestId, CredentialRequest assignment, String resource) {}

  public record Grant(UUID permitId, Instant leasedUntil, Instant retryAt) {}

  public record Report(
      UUID permitId, int status, Long remaining, Instant resetAt, Instant retryAt) {}

  private final GitHubBudgetJdbcRepository budgets;
  private final TransactionTemplate tx;
  private final CredentialVault vault;
  private final GitHubBudgetProperties properties;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private io.micrometer.core.instrument.MeterRegistry metrics;

  private void decision(String resource, String decision) {
    if (metrics != null)
      metrics
          .counter("rgm.github.budget.decisions", "resource", resource, "decision", decision)
          .increment();
  }

  public SharedGitHubBudget(
      GitHubBudgetJdbcRepository budgets, PlatformTransactionManager tm, CredentialVault vault) {
    this(budgets, tm, vault, new GitHubBudgetProperties(0, 0, 0, 0, null));
  }

  @org.springframework.beans.factory.annotation.Autowired
  public SharedGitHubBudget(
      GitHubBudgetJdbcRepository budgets,
      PlatformTransactionManager tm,
      CredentialVault vault,
      GitHubBudgetProperties properties) {
    this.budgets = budgets;
    this.tx = new TransactionTemplate(tm);
    this.vault = vault;
    this.properties = properties;
  }

  public Grant acquire(String node, Acquisition request) {
    if (request.requestId() == null
        || !Set.of("core", "search", "graphql").contains(request.resource()))
      throw new IllegalArgumentException("Invalid quota request");
    return tx.execute(status -> {
      String identity = vault.resolve(node, request.assignment()).quotaIdentity();
      budgets.advisoryLock(identity);
      Instant now = budgets.clockTimestamp();
      var previous = budgets.findPermit(request.requestId());
      if (!previous.isEmpty()) {
        var row = previous.getFirst();
        if (!node.equals(row.get("node_key"))
            || !request.assignment().attemptId().equals(row.get("attempt_id"))
            || !identity.equals(row.get("identity_key"))
            || !request.resource().equals(row.get("resource")))
          throw new IllegalArgumentException("Permit identity conflict");
        Instant until = ((Timestamp) row.get("leased_until")).toInstant();
        return new Grant(request.requestId(), until, until.isAfter(now) ? null : until);
      }
      boolean search = request.resource().equals("search");
      long ceiling = search ? properties.searchPerMinute() : properties.coreHourly();
      long window = search ? 60 : 3600;
      budgets.ensureBudgetRow(
          identity, request.resource(), ceiling, now.plusSeconds(window), now);
      var budget = budgets.lockBudget(identity, request.resource());
      Instant reset = ((Timestamp) budget.get("reset_at")).toInstant();
      long remaining = ((Number) budget.get("remaining")).longValue();
      if (!reset.isAfter(now)) {
        remaining = ceiling;
        reset = now.plusSeconds(window);
      }
      Instant retry = ((Timestamp) budget.get("next_request_at")).toInstant();
      if (budget.get("cooldown_until") != null) {
        Instant cooldown = ((Timestamp) budget.get("cooldown_until")).toInstant();
        if (cooldown.isAfter(retry)) retry = cooldown;
      }
      var active = budgets.activeLeaseUntil(identity);
      if (!active.isEmpty() && active.getFirst().toInstant().isAfter(retry))
        retry = active.getFirst().toInstant();
      if (remaining <= (search ? properties.searchReserve() : properties.coreReserve())
          && reset.isAfter(retry)) retry = reset;
      if (retry.isAfter(now)) {
        decision(request.resource(), "wait");
        return new Grant(null, null, retry);
      }
      budgets.deleteExpiredPermits(identity, now);
      Instant until = now.plus(properties.permitLease());
      budgets.updateBudgetAfterGrant(
          remaining - 1,
          reset,
          now.plusMillis((window * 1000 + ceiling - 1) / ceiling),
          now,
          identity,
          request.resource());
      budgets.insertPermit(
          request.requestId(),
          identity,
          node,
          request.assignment().attemptId().toString(),
          request.resource(),
          until);
      decision(request.resource(), "grant");
      return new Grant(request.requestId(), until, null);
    });
  }

  public boolean report(String node, Report report) {
    return Boolean.TRUE.equals(
        tx.execute(status -> {
          var identities = budgets.permitIdentities(report.permitId(), node);
          if (identities.isEmpty()) return false;
          budgets.advisoryLock(identities.getFirst());
          var rows = budgets.lockPermit(report.permitId(), node);
          if (rows.isEmpty()) return false;
          var row = rows.getFirst();
          String identity = (String) row.get("identity_key"),
              resource = (String) row.get("resource");
          // Out-of-order responses may only lower remaining or extend protective waits.
          if (report.remaining() != null && report.remaining() >= 0)
            budgets.lowerRemaining(report.remaining(), identity, resource);
          if (report.resetAt() != null)
            budgets.extendResetAt(report.resetAt(), identity, resource);
          Instant cooldown = report.retryAt();
          if ((report.status() == org.springframework.http.HttpStatus.FORBIDDEN.value()
                  || report.status()
                      == org.springframework.http.HttpStatus.TOO_MANY_REQUESTS.value())
              && cooldown == null) cooldown = Instant.now().plusSeconds(60);
          if (report.status() == org.springframework.http.HttpStatus.UNAUTHORIZED.value()
              || report.status() == org.springframework.http.HttpStatus.NOT_FOUND.value())
            cooldown = Instant.now().plusSeconds(30);
          if (cooldown != null) budgets.extendCooldown(cooldown, identity);
          budgets.insertReceipt(report.permitId());
          budgets.deletePermit(report.permitId());
          return true;
        }));
  }
}
