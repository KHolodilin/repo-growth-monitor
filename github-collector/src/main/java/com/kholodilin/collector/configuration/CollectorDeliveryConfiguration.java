package com.kholodilin.collector.configuration;

import com.kholodilin.collector.integration.delivery.ResultDeliveryClient;
import com.kholodilin.collector.model.CredentialSettings;
import com.kholodilin.collector.model.DeliverySettings;
import com.kholodilin.collector.repository.JobQueue;
import com.kholodilin.collector.service.ResultDeliveryService;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {
      "collector.delivery.enabled",
      "collector.runtime.enabled",
      "collector.credentials.enabled"
    },
    havingValue = "true")
@EnableConfigurationProperties(DeliveryProperties.class)
public class CollectorDeliveryConfiguration {
  static void requireLeaseExceedsTimeouts(
      DeliverySettings delivery, CredentialSettings credentials) {
    Duration timeouts = credentials.connectTimeout().plus(credentials.readTimeout()).plusSeconds(5);
    if (delivery.lease().compareTo(timeouts) <= 0) {
      throw new IllegalArgumentException("Delivery lease must exceed callback timeouts");
    }
  }

  @Bean
  DeliverySettings deliverySettings(DeliveryProperties properties) {
    return properties.toSettings();
  }

  @Bean
  ResultDeliveryClient resultDeliveryClient(
      DeliverySettings delivery, CredentialSettings credentials) {
    requireLeaseExceedsTimeouts(delivery, credentials);
    return new ResultDeliveryClient(
        delivery, credentials, ResultDeliveryClient.restClient(credentials));
  }

  @Bean
  ResultDeliveryService resultDeliveryService(
      JobQueue queue, DeliverySettings delivery, ResultDeliveryClient client) {
    return new ResultDeliveryService(queue, delivery, client);
  }

  @Bean
  SmartLifecycle collectorDeliveryLoop(ResultDeliveryService service, DeliverySettings delivery) {
    return new SmartLifecycle() {
      private ScheduledExecutorService loop;
      private volatile boolean running;

      public void start() {
        running = true;
        loop =
            Executors.newSingleThreadScheduledExecutor(r -> {
              Thread t = new Thread(r, "collector-result-delivery");
              t.setDaemon(true);
              return t;
            });
        loop.scheduleWithFixedDelay(
            () -> {
              try {
                service.poll("collector-delivery");
              } catch (RuntimeException ignored) {
              }
            },
            0,
            delivery.pollInterval().toMillis(),
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
