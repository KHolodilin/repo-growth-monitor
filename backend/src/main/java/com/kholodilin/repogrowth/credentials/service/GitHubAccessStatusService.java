package com.kholodilin.repogrowth.credentials.service;

import com.kholodilin.repogrowth.orchestration.CollectionRequestService;
import com.kholodilin.repogrowth.orchestration.CollectorNodeMonitor;
import com.kholodilin.repogrowth.orchestration.OrchestrationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reports whether GitHub credentials exist for the collector. Only a non-revoked vault credential
 * counts: the product process never holds or uses a GitHub token itself, and it cannot verify a
 * credential, so presence alone is never reported as a working token.
 *
 * <p>{@link TokenState#ORCHESTRATION_DISABLED} means collection commands are not wired. {@link
 * TokenState#COLLECTOR_UNAVAILABLE} means orchestration is on but no usable collector node is
 * present.
 */
@Slf4j
@Service
public class GitHubAccessStatusService {

  public enum TokenState {
    MISSING,
    UNVERIFIED,
    ORCHESTRATION_DISABLED,
    COLLECTOR_UNAVAILABLE
  }

  public record Status(boolean credentialsConfigured, TokenState tokenState) {}

  private final JdbcTemplate jdbc;
  private final ObjectProvider<CollectionRequestService> orchestration;
  private final ObjectProvider<OrchestrationProperties> orchestrationProperties;
  private final ObjectProvider<CollectorNodeMonitor> nodeMonitor;

  public GitHubAccessStatusService(
      JdbcTemplate jdbc,
      ObjectProvider<CollectionRequestService> orchestration,
      ObjectProvider<OrchestrationProperties> orchestrationProperties,
      ObjectProvider<CollectorNodeMonitor> nodeMonitor) {
    this.jdbc = jdbc;
    this.orchestration = orchestration;
    this.orchestrationProperties = orchestrationProperties;
    this.nodeMonitor = nodeMonitor;
  }

  public Status status() {
    if (!vaultCredentialConfigured()) {
      return new Status(false, TokenState.MISSING);
    }
    if (orchestration.getIfAvailable() == null) {
      return new Status(true, TokenState.ORCHESTRATION_DISABLED);
    }
    if (!collectorReachable()) {
      return new Status(true, TokenState.COLLECTOR_UNAVAILABLE);
    }
    return new Status(true, TokenState.UNVERIFIED);
  }

  private boolean collectorReachable() {
    OrchestrationProperties properties = orchestrationProperties.getIfAvailable();
    if (properties == null) {
      return false;
    }
    var enabled =
        properties.nodes().stream().filter(OrchestrationProperties.Node::enabled).toList();
    if (enabled.isEmpty()) {
      return false;
    }
    CollectorNodeMonitor monitor = nodeMonitor.getIfAvailable();
    if (monitor == null) {
      // Recovery probe is optional; configured enabled nodes are enough to dispatch.
      return true;
    }
    return enabled.stream().anyMatch(node -> monitor.eligible(node.key()));
  }

  private boolean vaultCredentialConfigured() {
    try {
      return !jdbc.queryForList("SELECT 1 FROM github_credential WHERE NOT revoked LIMIT 1")
          .isEmpty();
    } catch (DataAccessException e) {
      // Credentials disabled or the vault table is not migrated: nothing is configured.
      log.debug(
          "GitHub credential vault is not readable; treating credentials as not configured", e);
      return false;
    }
  }
}
