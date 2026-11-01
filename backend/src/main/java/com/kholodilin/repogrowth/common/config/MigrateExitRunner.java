package com.kholodilin.repogrowth.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.function.IntConsumer;

/**
 * One-shot Liquibase migrate container: apply changelogs then exit.
 */
@Component
@Profile("migrate")
@Order
public class MigrateExitRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrateExitRunner.class);

    private final ConfigurableApplicationContext context;
    private final IntConsumer exit;

    public MigrateExitRunner(ConfigurableApplicationContext context) {
        this(context, System::exit);
    }

    MigrateExitRunner(ConfigurableApplicationContext context, IntConsumer exit) {
        this.context = context;
        this.exit = exit;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Database migrations finished");
        int code = SpringApplication.exit(context, () -> 0);
        exit.accept(code);
    }
}
