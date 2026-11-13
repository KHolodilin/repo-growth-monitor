package com.kholodilin.repogrowth;

import com.kholodilin.bootstrap.CollectorRuntimeConfiguration;
import com.kholodilin.collector.CollectorModuleConfiguration;
import com.kholodilin.repogrowth.collection.worker.CollectionWorkerPool;
import com.kholodilin.repogrowth.search.worker.SearchWorkerPool;
import com.kholodilin.repogrowth.common.config.PlannerScheduling;
import com.kholodilin.repogrowth.collection.planner.DailyPlanner;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.common.config.GitHubProperties;
import com.kholodilin.repogrowth.common.observability.AppMetrics;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.stereotype.Controller;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeRolesTest {
    @Test void collectorProfileStartsWithoutProductDatabaseAuthControllersOrPlanner() {
        try (var context = new SpringApplicationBuilder(RepoGrowthMonitorApplication.class)
                .profiles("collector").run("--spring.main.banner-mode=off")) {
            assertThat(context.getBeansOfType(CollectorModuleConfiguration.class)).hasSize(1);
            assertThat(context.getBeansWithAnnotation(Controller.class)).isEmpty();
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(CollectionWorkerPool.class)).isEmpty();
            assertThat(context.getBeansOfType(SearchWorkerPool.class)).isEmpty();
            assertThat(context.getBeansOfType(DailyPlanner.class)).isEmpty();
        }
    }
    @Test void webOnlyConditionsExcludeLegacyLoopsAndScheduling() {
        new ApplicationContextRunner().withPropertyValues("rgm.collector.enabled=false", "rgm.planner.enabled=false")
            .withUserConfiguration(CollectionWorkerPool.class,SearchWorkerPool.class,PlannerScheduling.class,CollectorRuntimeConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(CollectionWorkerPool.class).doesNotHaveBean(SearchWorkerPool.class)
                    .doesNotHaveBean(PlannerScheduling.class).doesNotHaveBean(CollectorModuleConfiguration.class);
            });
    }
    @Test void combinedDefaultsIncludeCollectorBoundaryAndScheduling() {
        new ApplicationContextRunner().withUserConfiguration(CollectorRuntimeConfiguration.class,PlannerScheduling.class)
            .run(context -> assertThat(context).hasSingleBean(CollectorModuleConfiguration.class).hasSingleBean(PlannerScheduling.class));
    }
    @Test void disabledGithubNeverTouchesTransportEvenWhenTokenIsPresent() {
        RestClient transport = mock(RestClient.class);
        var client = new GitHubClient(transport,JsonMapper.builder().build(),
            new GitHubProperties("present","https://api.github.com",Duration.ofSeconds(1),Duration.ofSeconds(1),null),
            new AppMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(client,"collectorEnabled",false);
        assertThatThrownBy(() -> client.getRepository("owner","repo")).isInstanceOf(GitHubException.class).hasMessageContaining("disabled");
        assertThatThrownBy(() -> client.getSocialPreview("owner","repo")).isInstanceOf(GitHubException.class);
        assertThatThrownBy(() -> client.searchRepositories("java",10)).isInstanceOf(GitHubException.class);
        verifyNoInteractions(transport);
    }
}
