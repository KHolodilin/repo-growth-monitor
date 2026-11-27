package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.repogrowth.collection.repository.CollectorNodeMonitorJdbcRepository;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Admin-only diagnostics. Never returns tokens, source payloads or configured secret endpoints. */
@Service
@ConditionalOnProperty(name = "rgm.orchestration.enabled", havingValue = "true")
public class CollectorRecoveryDiagnosticsService {
  private final CollectorNodeMonitorJdbcRepository nodes;

  public CollectorRecoveryDiagnosticsService(CollectorNodeMonitorJdbcRepository nodes) {
    this.nodes = nodes;
  }

  public Map<String, Object> status() {
    return Map.of("nodes", nodes.nodeHealthRows(), "attempts", nodes.activeAttemptSummaries());
  }
}
