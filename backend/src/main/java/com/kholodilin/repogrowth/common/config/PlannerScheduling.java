package com.kholodilin.repogrowth.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Tests set {@code spring.task.scheduling.enabled=false}. The annotation has to live on this
 * configuration so that switch is honored. On the application class the planner still ticks and
 * writes {@code collection_run} rows while a test is cleaning the database.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "spring.task.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PlannerScheduling {
}
