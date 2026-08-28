package com.kholodilin.repogrowth.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
public abstract class AbstractPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("repogrowth")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("github.token", () -> "test-token");
        registry.add("collection.planner.from", () -> "00:00");
        registry.add("collection.planner.to", () -> "23:59");
        registry.add("collection.workers", () -> "0");
        registry.add("search.workers", () -> "0");
        registry.add("app.timezone", () -> "UTC");
    }
}
