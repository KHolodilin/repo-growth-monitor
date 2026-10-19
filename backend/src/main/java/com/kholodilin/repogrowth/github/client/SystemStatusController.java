package com.kholodilin.repogrowth.github.client;

import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.config.GitHubProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private final GitHubProperties properties;
    private final GitHubClient gitHubClient;
    private final PlanningWindow planningWindow;

    public SystemStatusController(GitHubProperties properties, GitHubClient gitHubClient, PlanningWindow planningWindow) {
        this.properties = properties;
        this.gitHubClient = gitHubClient;
        this.planningWindow = planningWindow;
    }

    @GetMapping("/status")
    public SystemStatusResponse status() {
        SystemStatusResponse.PlannerWindow planner = plannerWindow();
        GitHubClient.TokenStatus token = gitHubClient.tokenStatus();
        if (token == null) {
            boolean configured = properties.tokenConfigured();
            return new SystemStatusResponse(
                    configured,
                    gitHubClient.maskedToken(),
                    configured ? "OK" : "MISSING",
                    null,
                    planner
            );
        }
        return new SystemStatusResponse(
                properties.tokenConfigured(),
                gitHubClient.maskedToken(),
                token.state(),
                token.rateLimitReset(),
                planner
        );
    }

    private SystemStatusResponse.PlannerWindow plannerWindow() {
        return new SystemStatusResponse.PlannerWindow(
                planningWindow.zoneId().getId(),
                planningWindow.from().format(HOUR_MINUTE),
                planningWindow.to().format(HOUR_MINUTE),
                planningWindow.isOpen()
        );
    }

    public record SystemStatusResponse(
            boolean githubTokenConfigured,
            String githubTokenMasked,
            String tokenState,
            java.time.Instant rateLimitReset,
            PlannerWindow planner
    ) {
        public record PlannerWindow(String timezone, String from, String to, boolean open) {
        }
    }
}
