package com.kholodilin.collector;

import com.kholodilin.collector.configuration.CollectorCredentialConfiguration;
import com.kholodilin.collector.configuration.CollectorDeliveryConfiguration;
import com.kholodilin.collector.configuration.CollectorExecutionConfiguration;
import com.kholodilin.collector.configuration.CollectorHttpConfiguration;
import com.kholodilin.collector.configuration.CollectorMemoryConfiguration;
import com.kholodilin.collector.configuration.CollectorModuleConfiguration;
import com.kholodilin.collector.configuration.CollectorObservabilityConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Standalone collector node entrypoint - no product UI/planner/datasource. */
@SpringBootApplication(
    scanBasePackages = "com.kholodilin.collector",
    excludeName = {"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"})
@Import({
  CollectorModuleConfiguration.class,
  CollectorMemoryConfiguration.class,
  CollectorHttpConfiguration.class,
  CollectorCredentialConfiguration.class,
  CollectorExecutionConfiguration.class,
  CollectorDeliveryConfiguration.class,
  CollectorObservabilityConfiguration.class
})
public class GitHubCollectorApplication {
  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(GitHubCollectorApplication.class);
    // Standard application.yml / banner.txt; profile keeps library classpath from overriding
    // product config.
    app.setDefaultProperties(java.util.Map.of("spring.profiles.default", "collector-node"));
    app.run(args);
  }
}
