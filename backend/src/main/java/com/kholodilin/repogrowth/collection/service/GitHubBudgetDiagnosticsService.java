package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.repogrowth.collection.repository.GitHubBudgetJdbcRepository;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "rgm.credentials.enabled", havingValue = "true")
public class GitHubBudgetDiagnosticsService {
  private final GitHubBudgetJdbcRepository budget;

  public GitHubBudgetDiagnosticsService(GitHubBudgetJdbcRepository budget) {
    this.budget = budget;
  }

  public List<Map<String, Object>> budgets() {
    return budget.listBudgets();
  }
}
