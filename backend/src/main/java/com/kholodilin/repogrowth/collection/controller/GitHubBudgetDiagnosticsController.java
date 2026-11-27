package com.kholodilin.repogrowth.collection.controller;

import com.kholodilin.repogrowth.collection.service.GitHubBudgetDiagnosticsService;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "rgm.credentials.enabled", havingValue = "true")
public class GitHubBudgetDiagnosticsController {
  private final GitHubBudgetDiagnosticsService diagnostics;

  public GitHubBudgetDiagnosticsController(GitHubBudgetDiagnosticsService diagnostics) {
    this.diagnostics = diagnostics;
  }

  @GetMapping("/api/v1/admin/github-budget")
  public List<Map<String, Object>> budgets() {
    return diagnostics.budgets();
  }
}
