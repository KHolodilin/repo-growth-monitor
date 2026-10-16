package com.kholodilin.repogrowth.github.client;

import com.kholodilin.repogrowth.common.config.GitHubProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private final GitHubProperties properties;
    private final GitHubClient gitHubClient;

    public SystemStatusController(GitHubProperties properties, GitHubClient gitHubClient) {
        this.properties = properties;
        this.gitHubClient = gitHubClient;
    }

    @GetMapping("/status")
    public SystemStatusResponse status() {
        GitHubClient.TokenStatus token = gitHubClient.tokenStatus();
        if (token == null) {
            boolean configured = properties.tokenConfigured();
            return new SystemStatusResponse(configured, gitHubClient.maskedToken(), configured ? "OK" : "MISSING", null);
        }
        return new SystemStatusResponse(
                properties.tokenConfigured(),
                gitHubClient.maskedToken(),
                token.state(),
                token.rateLimitReset()
        );
    }

    public record SystemStatusResponse(
            boolean githubTokenConfigured,
            String githubTokenMasked,
            String tokenState,
            java.time.Instant rateLimitReset
    ) {
    }
}
