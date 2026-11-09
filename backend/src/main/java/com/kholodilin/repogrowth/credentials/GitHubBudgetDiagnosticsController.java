package com.kholodilin.repogrowth.credentials;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
@RestController
@ConditionalOnProperty(name="rgm.credentials.enabled",havingValue="true")
public class GitHubBudgetDiagnosticsController {
    private final JdbcTemplate jdbc;
    public GitHubBudgetDiagnosticsController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping("/api/v1/admin/github-budget")
    public List<Map<String,Object>> budgets(){return jdbc.queryForList("SELECT identity_key,resource,remaining,reset_at,next_request_at,cooldown_until FROM github_budget ORDER BY identity_key,resource");}
}
