package com.kholodilin.repogrowth.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ManagementSecurityIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("repogrowth")
            .withUsername("postgres")
            .withPassword("postgres");

    static {
        POSTGRES.start();
    }

    @LocalManagementPort
    int managementPort;

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("github.token", () -> "test-token");
        registry.add("collection.workers", () -> "0");
        registry.add("search.workers", () -> "0");
        registry.add("spring.task.scheduling.enabled", () -> "false");
        registry.add("collection.planner.enabled", () -> "false");
        registry.add("auth.provider", () -> "mock");
        registry.add("auth.production", () -> "false");
        registry.add("management.server.port", () -> "0");
        registry.add("MANAGEMENT_USER", () -> "actuator");
        registry.add("MANAGEMENT_PASSWORD", () -> "s3cret");
    }

    @Test
    void healthIsOpenOnManagementPort() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UP");
    }

    @Test
    void metricsRequireBasicAuth() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> denied = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(denied.statusCode()).isEqualTo(401);

        String basic = Base64.getEncoder().encodeToString("actuator:s3cret".getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> ok = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/metrics"))
                        .header("Authorization", "Basic " + basic)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("names");
    }

    @Test
    void actuatorOnAppPortIsDenied() throws Exception {
        mockMvc.perform(get("/actuator/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
