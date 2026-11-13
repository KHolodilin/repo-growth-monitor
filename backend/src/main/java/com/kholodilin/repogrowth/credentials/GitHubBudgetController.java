package com.kholodilin.repogrowth.credentials;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
@RestController
@ConditionalOnProperty(name="rgm.credentials.enabled",havingValue="true")
@RequestMapping("/internal/v1/github-budget")
public class GitHubBudgetController {
 private final SharedGitHubBudget budget;
 public GitHubBudgetController(SharedGitHubBudget budget){this.budget=budget;}
 @PostMapping("/acquire") public SharedGitHubBudget.Grant acquire(@RequestBody SharedGitHubBudget.Acquisition request,Authentication auth){return budget.acquire(auth.getName(),request);}
 @PostMapping("/report") public ResponseEntity<Void> report(@RequestBody SharedGitHubBudget.Report report,Authentication auth){return budget.report(auth.getName(),report)?ResponseEntity.noContent().build():ResponseEntity.notFound().build();}
}
