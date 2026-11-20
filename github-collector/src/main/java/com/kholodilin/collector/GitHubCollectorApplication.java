package com.kholodilin.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Standalone collector node entrypoint — no product UI/planner/datasource. */
@SpringBootApplication(
    scanBasePackages = "com.kholodilin.collector",
    exclude = {DataSourceAutoConfiguration.class})
@Import({
  CollectorModuleConfiguration.class,
  CollectorMemoryConfiguration.class,
  com.kholodilin.collector.acceptance.CollectorHttpConfiguration.class,
  com.kholodilin.collector.credentials.CollectorCredentialConfiguration.class,
  com.kholodilin.collector.execution.CollectorExecutionConfiguration.class,
  com.kholodilin.collector.delivery.CollectorDeliveryConfiguration.class,
  CollectorObservabilityConfiguration.class
})
public class GitHubCollectorApplication {
  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(GitHubCollectorApplication.class);
    // Avoid clashing with product application.yml when this module is also a library dependency.
    app.setDefaultProperties(java.util.Map.of("spring.config.name", "github-collector"));
    app.run(args);
  }
}
