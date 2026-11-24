package com.kholodilin.collector.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.kholodilin.collector.integration.credentials.CredentialCache;
import com.kholodilin.collector.integration.github.AssignedGithubClient;
import com.kholodilin.collector.integration.github.GitHubObservationMapper;
import com.kholodilin.collector.integration.github.GitHubSourceReader;
import com.kholodilin.collector.model.CredentialSettings;
import com.kholodilin.collector.repository.ObservationStore;
import com.kholodilin.collector.service.JobExecutionService;
import com.kholodilin.collector.service.ObservationCheckpoint;
import com.kholodilin.collector.service.collection.AssignedTaskCollectionService;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runtime wiring must keep service above integration: integration beans are not constructed with
 * service types, and collection orchestration owns the checkpoint.
 */
class ExecutionLayerWiringTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
          .withBean(CredentialCache.class, () -> mock(CredentialCache.class))
          .withBean(
              CredentialSettings.class,
              () ->
                  new CredentialSettings(
                      Duration.ofHours(2),
                      100,
                      1000,
                      Duration.ofSeconds(5),
                      Duration.ofSeconds(1),
                      Duration.ofSeconds(1),
                      List.of(
                          new CredentialSettings.Origin(
                              "rgm",
                              URI.create("https://rgm.example"),
                              "node-service-secret-0123456789012345"))))
          .withUserConfiguration(
              CollectorMemoryConfiguration.class, CollectorExecutionConfiguration.class)
          .withPropertyValues(
              "collector.runtime.enabled=true",
              "collector.execution.enabled=true",
              "collector.credentials.enabled=true",
              "collector.capacity.max-jobs=10",
              "collector.capacity.max-queue-bytes=1048576",
              "collector.capacity.max-request-bytes=65536");

  @Test
  void executionRegistersDownwardCollectionChain() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasSingleBean(GitHubSourceReader.class);
      assertThat(context).hasSingleBean(GitHubObservationMapper.class);
      assertThat(context).hasSingleBean(AssignedGithubClient.class);
      assertThat(context).hasSingleBean(ObservationStore.class);
      assertThat(context).hasSingleBean(ObservationCheckpoint.class);
      assertThat(context).hasSingleBean(AssignedTaskCollectionService.class);
      assertThat(context).hasSingleBean(JobExecutionService.class);
    });
  }

  @Test
  void integrationGithubClientConstructorStaysOutsideServiceAndRepository() {
    Constructor<?> ctor = AssignedGithubClient.class.getDeclaredConstructors()[0];
    for (Class<?> type : ctor.getParameterTypes()) {
      assertThat(type.getName())
          .doesNotStartWith("com.kholodilin.collector.service.")
          .doesNotStartWith("com.kholodilin.collector.repository.");
    }
  }

  @Test
  void observationCheckpointIsOwnedByServiceWithoutGithubReader() {
    Constructor<?> ctor = ObservationCheckpoint.class.getDeclaredConstructors()[0];
    List<String> params =
        java.util.Arrays.stream(ctor.getParameterTypes()).map(Class::getName).toList();
    assertThat(params)
        .contains(ObservationStore.class.getName(), JsonMapper.class.getName())
        .doesNotContain(GitHubSourceReader.class.getName())
        .doesNotContain(AssignedGithubClient.class.getName());
    assertThat(params)
        .noneMatch(name -> name.equals(AssignedTaskCollectionService.class.getName()));
  }
}
