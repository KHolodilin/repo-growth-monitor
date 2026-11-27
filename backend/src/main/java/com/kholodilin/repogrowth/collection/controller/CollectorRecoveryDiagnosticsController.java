package com.kholodilin.repogrowth.collection.controller;

import com.kholodilin.repogrowth.collection.service.CollectorRecoveryDiagnosticsService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Admin-only diagnostics. Never returns tokens, source payloads or configured secret endpoints. */
@RestController
@ConditionalOnProperty(name = "rgm.orchestration.enabled", havingValue = "true")
public class CollectorRecoveryDiagnosticsController {
  private final CollectorRecoveryDiagnosticsService diagnostics;

  public CollectorRecoveryDiagnosticsController(CollectorRecoveryDiagnosticsService diagnostics) {
    this.diagnostics = diagnostics;
  }

  @GetMapping("/api/v1/admin/collectors")
  public Map<String, Object> status() {
    return diagnostics.status();
  }
}
