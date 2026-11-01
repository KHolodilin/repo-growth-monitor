package com.kholodilin.repogrowth.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityOidcBootIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("repogrowth")
            .withUsername("postgres")
            .withPassword("postgres");

    static {
        POSTGRES.start();
    }

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
        registry.add("auth.provider", () -> "zitadel");
        registry.add("auth.production", () -> "true");
        registry.add("auth.oidc.issuer-uri", () -> "https://idp.example");
        registry.add("auth.oidc.client-id", () -> "client");
        registry.add("auth.oidc.client-secret", () -> "secret");
        registry.add("springdoc.api-docs.enabled", () -> "false");
        registry.add("springdoc.swagger-ui.enabled", () -> "false");
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ClientRegistrationRepository clients;
    @Autowired
    JdbcClient jdbcClient;

    @Test
    void bootsOidcSecurityAndServesLogin() throws Exception {
        assertThat(clients.findByRegistrationId("oidc")).isNotNull();
        mockMvc.perform(get("/login")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isOk());
        // Anonymous + denyAll is answered by the auth entry point (401) rather than 403.
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        assertThat(jdbcClient.sql("SELECT count(*) FROM app_users").query(Long.class).single()).isZero();
    }
}
