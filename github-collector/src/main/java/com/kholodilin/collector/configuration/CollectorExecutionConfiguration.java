package com.kholodilin.collector.configuration;

import com.kholodilin.collector.integration.credentials.CredentialCache;
import com.kholodilin.collector.integration.github.AssignedGithubSupport;
import com.kholodilin.collector.integration.github.GitHubSourceReader;
import com.kholodilin.collector.integration.github.LocalGitHubPacer;
import com.kholodilin.collector.model.CredentialSettings;
import com.kholodilin.collector.model.ExecutionSettings;
import com.kholodilin.collector.repository.InMemoryObservationStore;
import com.kholodilin.collector.repository.JobQueue;
import com.kholodilin.collector.repository.ObservationStore;
import com.kholodilin.collector.service.AssignedTaskCollectionService;
import com.kholodilin.collector.service.JobExecutionService;
import com.kholodilin.collector.service.ObservationCheckpoint;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {
      "collector.execution.enabled",
      "collector.runtime.enabled",
      "collector.credentials.enabled"
    },
    havingValue = "true")
@EnableConfigurationProperties({ExecutionProperties.class, GitHubClientProperties.class})
public class CollectorExecutionConfiguration {
  @Bean
  ExecutionSettings executionSettings(ExecutionProperties properties) {
    return properties.toSettings();
  }

  @Bean
  LocalGitHubPacer localGitHubPacer(GitHubClientProperties github) {
    return new LocalGitHubPacer(github.pacingSettings());
  }

  @Bean
  ObservationStore observationStore(ExecutionSettings settings) {
    return new InMemoryObservationStore(settings.maximumPayloadBytes() - 512);
  }

  @Bean
  GitHubSourceReader gitHubSourceReader(
      CredentialCache cache,
      CredentialSettings credentials,
      ExecutionSettings settings,
      LocalGitHubPacer pacer,
      JsonMapper json) {
    return new GitHubSourceReader(
        cache, credentials, settings, pacer, GitHubSourceReader.restClient(settings), json);
  }

  @Bean
  AssignedGithubSupport assignedGithubSupport(ExecutionSettings settings, JsonMapper json) {
    return new AssignedGithubSupport(settings, json);
  }

  @Bean
  ObservationCheckpoint observationCheckpoint(
      ObservationStore store, GitHubSourceReader github, JsonMapper json) {
    return new ObservationCheckpoint(store, github, json);
  }

  @Bean
  AssignedTaskCollectionService assignedTaskCollectionService(
      ObservationCheckpoint checkpoint, AssignedGithubSupport github) {
    return new AssignedTaskCollectionService(checkpoint, github);
  }

  @Bean(destroyMethod = "close")
  JobExecutionService jobExecutionService(
      JobQueue queue,
      AssignedTaskCollectionService collector,
      ExecutionSettings settings,
      GitHubClientProperties github,
      JsonMapper json) {
    return new JobExecutionService(queue, collector, settings, github.retrySettings(), json);
  }

  @Bean
  SmartLifecycle collectorExecutionLoop(JobExecutionService executor, ExecutionSettings settings) {
    return new SmartLifecycle() {
      private ScheduledExecutorService loop;
      private volatile boolean running;

      public void start() {
        running = true;
        loop =
            Executors.newSingleThreadScheduledExecutor(r -> {
              Thread t = new Thread(r, "collector-assigned-execution");
              t.setDaemon(true);
              return t;
            });
        loop.scheduleWithFixedDelay(
            () -> {
              try {
                executor.poll("collector-executor");
              } catch (RuntimeException ignored) {
              }
            },
            0,
            settings.pollInterval().toMillis(),
            TimeUnit.MILLISECONDS);
      }

      public void stop() {
        running = false;
        if (loop != null) loop.shutdownNow();
      }

      public boolean isRunning() {
        return running;
      }
    };
  }
}
