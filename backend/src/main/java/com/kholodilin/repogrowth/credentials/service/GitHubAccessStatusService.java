package com.kholodilin.repogrowth.credentials.service;

import com.kholodilin.repogrowth.collection.configuration.OrchestrationProperties;
import com.kholodilin.repogrowth.collection.service.CollectionRequestService;
import com.kholodilin.repogrowth.collection.service.CollectorNodeMonitor;
import com.kholodilin.repogrowth.credentials.model.GitHubAccessStatus;
import com.kholodilin.repogrowth.credentials.model.TokenState;
import com.kholodilin.repogrowth.credentials.repository.CredentialJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
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

  /** @deprecated Prefer {@link TokenState}. */
  public enum TokenState {
    MISSING,
    UNVERIFIED,
    ORCHESTRATION_DISABLED,
    COLLECTOR_UNAVAILABLE;

    public com.kholodilin.repogrowth.credentials.model.TokenState toModel() {
      return com.kholodilin.repogrowth.credentials.model.TokenState.valueOf(name());
    }

    public static TokenState from(com.kholodilin.repogrowth.credentials.model.TokenState state) {
      return TokenState.valueOf(state.name());
    }
  }

  /** @deprecated Prefer {@link GitHubAccessStatus}. */
  public record Status(boolean credentialsConfigured, TokenState tokenState) {
    public static Status from(GitHubAccessStatus status) {
      return new Status(status.credentialsConfigured(), TokenState.from(status.tokenState()));
    }
  }

  private final CredentialJdbcRepository credentials;
  private final ObjectProvider<CollectionRequestService> orchestration;
  private final ObjectProvider<OrchestrationProperties> orchestrationProperties;
  private final ObjectProvider<CollectorNodeMonitor> nodeMonitor;

  public GitHubAccessStatusService(
      CredentialJdbcRepository credentials,
      ObjectProvider<CollectionRequestService> orchestration,
      ObjectProvider<OrchestrationProperties> orchestrationProperties,
      ObjectProvider<CollectorNodeMonitor> nodeMonitor) {
    this.credentials = credentials;
    this.orchestration = orchestration;
    this.orchestrationProperties = orchestrationProperties;
    this.nodeMonitor = nodeMonitor;
  }

  public Status status() {
    return Status.from(accessStatus());
  }

  public GitHubAccessStatus accessStatus() {
    if (!vaultCredentialConfigured()) {
      return new GitHubAccessStatus(false, com.kholodilin.repogrowth.credentials.model.TokenState.MISSING);
    }
    if (orchestration.getIfAvailable() == null) {
      return new GitHubAccessStatus(
          true, com.kholodilin.repogrowth.credentials.model.TokenState.ORCHESTRATION_DISABLED);
    }
    if (!collectorReachable()) {
      return new GitHubAccessStatus(
          true, com.kholodilin.repogrowth.credentials.model.TokenState.COLLECTOR_UNAVAILABLE);
    }
    return new GitHubAccessStatus(
        true, com.kholodilin.repogrowth.credentials.model.TokenState.UNVERIFIED);
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
      return credentials.hasActiveCredential();
    } catch (DataAccessException e) {
      // Credentials disabled or the vault table is not migrated: nothing is configured.
      log.debug(
          "GitHub credential vault is not readable; treating credentials as not configured", e);
      return false;
    }
  }
}
