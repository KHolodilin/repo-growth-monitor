package com.kholodilin.collector;

import com.kholodilin.collector.acceptance.AcceptanceProperties;
import com.kholodilin.collector.idempotency.InMemoryPersistenceStore;
import com.kholodilin.collector.queue.InMemoryJobQueue;
import com.kholodilin.collector.queue.JobQueue;
import com.kholodilin.collector.search.SearchProperties;
import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.core.DefaultIdempotencyServiceBuilder;
import com.kholodilin.idempotency.jackson.*;
import com.kholodilin.idempotency.spi.PersistenceStore;
import com.kholodilin.idempotency.spi.TransactionContext;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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
  JobQueue collectorJobQueue(CapacityProperties capacity) {
    return new InMemoryJobQueue(
        capacity.maxJobs(),
        capacity.maxQueueBytes(),
        (int) Math.min(Integer.MAX_VALUE, capacity.maxRequestBytes()));
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
