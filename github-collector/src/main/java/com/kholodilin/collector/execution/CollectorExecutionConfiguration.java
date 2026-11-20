package com.kholodilin.collector.execution;

import com.kholodilin.collector.credentials.*;
import com.kholodilin.collector.github.GitHubClientProperties;
import com.kholodilin.collector.queue.JobQueue;
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
  LocalGitHubPacer localGitHubPacer(GitHubClientProperties github) {
    return new LocalGitHubPacer(github.pacing());
  }

  @Bean
  ObservationStore observationStore(ExecutionProperties properties) {
    return new InMemoryObservationStore(properties.maximumPayloadBytes() - 512);
  }

  @Bean
  SourceReader githubSourceReader(
      ObservationStore store,
      CredentialCache cache,
      CollectorCredentialProperties credentials,
      ExecutionProperties properties,
      LocalGitHubPacer pacer,
      JsonMapper json) {
    return new GitHubSourceReader(
        store,
        cache,
        credentials,
        properties,
        pacer,
        GitHubSourceReader.restClient(properties),
        json);
  }

  @Bean
  AssignedTaskCollector assignedTaskCollector(
      SourceReader source, ExecutionProperties properties, JsonMapper json) {
    return new AssignedTaskCollector(source, properties, json);
  }

  @Bean(destroyMethod = "close")
  AssignedJobExecutor assignedJobExecutor(
      JobQueue queue,
      AssignedTaskCollector collector,
      ExecutionProperties properties,
      GitHubClientProperties github,
      JsonMapper json) {
    return new AssignedJobExecutor(queue, collector, properties, github, json);
  }

  @Bean
  SmartLifecycle collectorExecutionLoop(
      AssignedJobExecutor executor, ExecutionProperties properties) {
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
            properties.pollInterval().toMillis(),
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
