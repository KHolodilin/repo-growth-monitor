package com.kholodilin.repogrowth;

import com.kholodilin.repogrowth.common.config.AppProperties;
import com.kholodilin.repogrowth.common.config.CollectionProperties;
import com.kholodilin.repogrowth.common.config.CollectorSearchProperties;
import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.common.config.TrafficProperties;
import com.kholodilin.repogrowth.identity.cli.UsersCli;
import com.kholodilin.repogrowth.identity.service.UserAccountService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication(
    excludeName = {
      "com.kholodilin.idempotency.autoconfigure.IdempotencyJacksonAutoConfiguration",
      "com.kholodilin.idempotency.autoconfigure.IdempotencyJdbcAutoConfiguration",
      "com.kholodilin.idempotency.autoconfigure.IdempotencyCaffeineAutoConfiguration",
      "com.kholodilin.idempotency.autoconfigure.IdempotencyRedisAutoConfiguration",
      "com.kholodilin.idempotency.autoconfigure.IdempotencyMetricsAutoConfiguration",
      "com.kholodilin.idempotency.autoconfigure.IdempotencyCleanupAutoConfiguration",
      "com.kholodilin.idempotency.autoconfigure.IdempotencyAutoConfiguration"
    })
@ComponentScan(
    basePackages = "com.kholodilin.repogrowth",
    excludeFilters = {
      @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
      @ComponentScan.Filter(
          type = FilterType.CUSTOM,
          classes = AutoConfigurationExcludeFilter.class)
    })
@EnableConfigurationProperties({
  AppProperties.class,
  CollectionProperties.class,
  SearchProperties.class,
  CollectorSearchProperties.class,
  TrafficProperties.class
})
public class RepoGrowthMonitorApplication {

  public static void main(String[] args) {
    if (args.length > 0 && "users".equals(args[0])) {
      SpringApplication app = new SpringApplication(RepoGrowthMonitorApplication.class);
      app.setWebApplicationType(WebApplicationType.NONE);
      app.setAdditionalProfiles("cli");
      try (ConfigurableApplicationContext context = app.run(args)) {
        int code = new UsersCli(context.getBean(UserAccountService.class)).run(args);
        System.exit(SpringApplication.exit(context, () -> code));
      }
      return;
    }
    SpringApplication.run(RepoGrowthMonitorApplication.class, args);
  }
}
