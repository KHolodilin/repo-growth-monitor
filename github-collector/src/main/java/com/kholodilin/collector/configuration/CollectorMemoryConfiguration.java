package com.kholodilin.collector.configuration;

import com.kholodilin.collector.model.AcceptanceSettings;
import com.kholodilin.collector.repository.InMemoryJobQueue;
import com.kholodilin.collector.repository.InMemoryPersistenceStore;
import com.kholodilin.collector.repository.JobQueue;
import com.kholodilin.collector.service.JobExecutionService;
import com.kholodilin.collector.service.RecoveryService;
import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.core.DefaultIdempotencyServiceBuilder;
import com.kholodilin.idempotency.jackson.*;
import com.kholodilin.idempotency.spi.PersistenceStore;
import com.kholodilin.idempotency.spi.TransactionContext;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/** In-memory collector runtime: queue and idempotency (no PostgreSQL). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "collector.runtime.enabled", havingValue = "true")
@EnableConfigurationProperties({
  CapacityProperties.class,
  SearchProperties.class,
  AcceptanceProperties.class
})
public class CollectorMemoryConfiguration {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock collectorClock() {
    return Clock.systemUTC();
  }

  @Bean
  JobQueue collectorJobQueue(CapacityProperties capacity, Clock clock, JsonMapper json) {
    return new InMemoryJobQueue(
        capacity.maxJobs(),
        capacity.maxQueueBytes(),
        (int) Math.min(Integer.MAX_VALUE, capacity.maxRequestBytes()),
        clock,
        json);
  }

  @Bean
  AcceptanceSettings collectorAcceptanceSettings(AcceptanceProperties properties) {
    return properties.toSettings();
  }

  @Bean
  RecoveryService collectorRecoveryService(
      JobQueue queue, ObjectProvider<JobExecutionService> execution) {
    return new RecoveryService(queue, execution);
  }

  @Bean(name = "collectorIdempotencyStore", defaultCandidate = false)
  PersistenceStore collectorIdempotencyStore() {
    return new InMemoryPersistenceStore();
  }

  @Bean(name = "collectorIdempotency", defaultCandidate = false)
  IdempotencyService collectorIdempotency(
      @Qualifier("collectorIdempotencyStore") PersistenceStore store, Environment env) {
    Duration ttl =
        env.getProperty("collector.idempotency.retention", Duration.class, Duration.ofDays(7));
    if (ttl == null || ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("Idempotency retention must be positive");
    }
    return new DefaultIdempotencyServiceBuilder(store)
        .fingerprintStrategy(new CanonicalJsonFingerprintStrategy())
        .serializer(new JacksonIdempotencySerializer())
        .transactionContext(TransactionContext.IMMEDIATE)
        .persistenceTtl(ttl)
        .localCache(null)
        .distributedCache(null)
        .build();
  }
}
