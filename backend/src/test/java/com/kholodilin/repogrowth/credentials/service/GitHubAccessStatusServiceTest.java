package com.kholodilin.repogrowth.credentials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.collection.configuration.OrchestrationProperties;
import com.kholodilin.repogrowth.collection.service.CollectionRequestService;
import com.kholodilin.repogrowth.collection.service.CollectorNodeMonitor;
import com.kholodilin.repogrowth.credentials.service.GitHubAccessStatusService.TokenState;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

class GitHubAccessStatusServiceTest {

  private static final String ACTIVE_CREDENTIAL =
      "SELECT 1 FROM github_credential WHERE NOT revoked LIMIT 1";

  private JdbcTemplate jdbc;
  private ObjectProvider<CollectionRequestService> orchestration;
  private ObjectProvider<OrchestrationProperties> orchestrationProperties;
  private ObjectProvider<CollectorNodeMonitor> nodeMonitor;
  private GitHubAccessStatusService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    orchestration = mock(ObjectProvider.class);
    orchestrationProperties = mock(ObjectProvider.class);
    nodeMonitor = mock(ObjectProvider.class);
    service =
        new GitHubAccessStatusService(jdbc, orchestration, orchestrationProperties, nodeMonitor);
  }

  @Test
  void reportsMissingWhenNoVaultCredentialExists() {
    when(jdbc.queryForList(ACTIVE_CREDENTIAL)).thenReturn(List.of());

    assertThat(service.status())
        .isEqualTo(new GitHubAccessStatusService.Status(false, TokenState.MISSING));
    verify(orchestration, never()).getIfAvailable();
  }

  @Test
  void reportsMissingWhenTheCredentialTableCannotBeRead() {
    when(jdbc.queryForList(ACTIVE_CREDENTIAL))
        .thenThrow(
            new BadSqlGrammarException("probe", ACTIVE_CREDENTIAL, new java.sql.SQLException()));

    assertThat(service.status())
        .isEqualTo(new GitHubAccessStatusService.Status(false, TokenState.MISSING));
    verify(orchestration, never()).getIfAvailable();
  }

  @Test
  void reportsOrchestrationDisabledWhenCollectionCommandsAreNotWired() {
    when(jdbc.queryForList(ACTIVE_CREDENTIAL)).thenReturn(List.of(Map.of("?column?", 1)));
    when(orchestration.getIfAvailable()).thenReturn(null);

    assertThat(service.status())
        .isEqualTo(new GitHubAccessStatusService.Status(true, TokenState.ORCHESTRATION_DISABLED));
  }

  @Test
  void reportsCollectorUnavailableWhenNoEnabledNodesAreConfigured() {
    when(jdbc.queryForList(ACTIVE_CREDENTIAL)).thenReturn(List.of(Map.of("?column?", 1)));
    when(orchestration.getIfAvailable()).thenReturn(mock(CollectionRequestService.class));
    when(orchestrationProperties.getIfAvailable())
        .thenReturn(
            new OrchestrationProperties(
                true, null, null, null, null, null, null, null, true, List.of(), List.of()));

    assertThat(service.status())
        .isEqualTo(new GitHubAccessStatusService.Status(true, TokenState.COLLECTOR_UNAVAILABLE));
  }

  @Test
  void reportsCollectorUnavailableWhenMonitorSaysNoNodeIsEligible() {
    when(jdbc.queryForList(ACTIVE_CREDENTIAL)).thenReturn(List.of(Map.of("?column?", 1)));
    when(orchestration.getIfAvailable()).thenReturn(mock(CollectionRequestService.class));
    var node =
        new OrchestrationProperties.Node(
            "node-a", "default", URI.create("http://collector.example"), true, "token");
    when(orchestrationProperties.getIfAvailable())
        .thenReturn(
            new OrchestrationProperties(
                true, null, null, null, null, null, null, null, true, List.of(node), List.of()));
    CollectorNodeMonitor monitor = mock(CollectorNodeMonitor.class);
    when(nodeMonitor.getIfAvailable()).thenReturn(monitor);
    when(monitor.eligible("node-a")).thenReturn(false);

    assertThat(service.status())
        .isEqualTo(new GitHubAccessStatusService.Status(true, TokenState.COLLECTOR_UNAVAILABLE));
  }

  @Test
  void aStoredCredentialWithAReachableCollectorIsUnverifiedNotOk() {
    when(jdbc.queryForList(ACTIVE_CREDENTIAL)).thenReturn(List.of(Map.of("?column?", 1)));
    when(orchestration.getIfAvailable()).thenReturn(mock(CollectionRequestService.class));
    var node =
        new OrchestrationProperties.Node(
            "node-a", "default", URI.create("http://collector.example"), true, "token");
    when(orchestrationProperties.getIfAvailable())
        .thenReturn(
            new OrchestrationProperties(
                true, null, null, null, null, null, null, null, true, List.of(node), List.of()));
    when(nodeMonitor.getIfAvailable()).thenReturn(null);

    assertThat(service.status())
        .isEqualTo(new GitHubAccessStatusService.Status(true, TokenState.UNVERIFIED));
  }
}
