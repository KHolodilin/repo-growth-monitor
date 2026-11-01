package com.kholodilin.repogrowth.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * After Liquibase (migrate profile), grant DML on public schema to the runtime role.
 */
@Component
@Profile("migrate")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RuntimeGrantsRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RuntimeGrantsRunner.class);

    private final JdbcTemplate jdbc;
    private final String runtimeUser;

    public RuntimeGrantsRunner(
            JdbcTemplate jdbc,
            @Value("${POSTGRES_RUNTIME_USER:repogrowth}") String runtimeUser
    ) {
        this.jdbc = jdbc;
        this.runtimeUser = runtimeUser;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(runtimeUser)) {
            return;
        }
        String user = runtimeUser.replaceAll("[^a-zA-Z0-9_]", "");
        if (!user.equals(runtimeUser)) {
            throw new IllegalStateException("Invalid POSTGRES_RUNTIME_USER");
        }
        log.info("Granting DML on public schema to {}", user);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + user);
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + user);
        jdbc.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + user);
        jdbc.execute(
                "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + user
        );
        jdbc.execute(
                "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO " + user
        );
    }
}
