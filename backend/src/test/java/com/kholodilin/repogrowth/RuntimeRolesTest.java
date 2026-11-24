package com.kholodilin.repogrowth;

import static org.assertj.core.api.Assertions.*;

import com.kholodilin.repogrowth.common.config.PlannerScheduling;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RuntimeRolesTest {
  @Test
  void productClasspathDoesNotImportCollectorRuntimeConfiguration() {
    assertThatThrownBy(
            () -> Class.forName("com.kholodilin.bootstrap.CollectorRuntimeConfiguration"))
        .isInstanceOf(ClassNotFoundException.class);
  }

  @Test
  void productClasspathDoesNotDependOnCollectorImplementation() {
    for (String type :
        new String[] {
          "com.kholodilin.collector.configuration.CollectorModuleConfiguration",
          "com.kholodilin.collector.configuration.SearchProperties",
          "com.kholodilin.collector.repository.ScopedIdempotency",
          "com.kholodilin.collector.service.CollectorAcceptanceService"
        }) {
      assertThatThrownBy(() -> Class.forName(type)).isInstanceOf(ClassNotFoundException.class);
    }
  }

  @Test
  void productScanPackageDoesNotIncludeCollectorModules() {
    assertThat(
            RepoGrowthMonitorApplication.class
                .getAnnotation(org.springframework.context.annotation.ComponentScan.class)
                .basePackages())
        .containsExactly("com.kholodilin.repogrowth");
  }

  @Test
  void webOnlyConditionsExcludeSchedulingWhenPlannerDisabled() {
    new ApplicationContextRunner()
        .withPropertyValues("rgm.planner.enabled=false")
        .withUserConfiguration(PlannerScheduling.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(PlannerScheduling.class);
        });
  }

  @Test
  void productDefaultsKeepPlannerSchedulingWithoutCollectorModules() {
    new ApplicationContextRunner()
        .withUserConfiguration(PlannerScheduling.class)
        .run(context -> assertThat(context).hasSingleBean(PlannerScheduling.class));
  }
}
