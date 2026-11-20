package com.kholodilin.collector.credentials;

import com.github.benmanes.caffeine.cache.*;
import com.kholodilin.collector.github.GitHubClientProperties;
import io.github.bucket4j.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local protection supplements the durable RGM grant; it does not allocate shared quota. */
public final class LocalGitHubPacer {
  private record Key(String identity, String resource) {}

  private final Cache<Key, Bucket> buckets =
      Caffeine.newBuilder().maximumSize(10000).expireAfterAccess(Duration.ofHours(1)).build();
  private final Semaphore concurrent;
  private final GitHubClientProperties.Pacing pacing;

  public LocalGitHubPacer() {
    this(new GitHubClientProperties(null, null).pacing());
  }

  public LocalGitHubPacer(GitHubClientProperties.Pacing pacing) {
    this.pacing = Objects.requireNonNull(pacing);
    this.concurrent = new Semaphore(pacing.concurrency(), true);
  }

  public Optional<CredentialedGitHubRequest.Permit> tryAcquire(String identity, String resource) {
    if (identity == null
        || identity.isBlank()
        || !Set.of("core", "search", "graphql").contains(resource))
      throw new IllegalArgumentException("Invalid pacing identity/resource");
    if (!concurrent.tryAcquire()) return Optional.empty();
    Duration interval = pacing.interval(resource);
    Bucket bucket =
        buckets.get(
            new Key(identity, resource),
            key ->
                Bucket.builder()
                    .addLimit(Bandwidth.builder().capacity(1).refillGreedy(1, interval).build())
                    .build());
    if (!bucket.tryConsume(1)) {
      concurrent.release();
      return Optional.empty();
    }
    AtomicBoolean closed = new AtomicBoolean();
    return Optional.of(() -> {
      if (closed.compareAndSet(false, true)) concurrent.release();
    });
  }
}
