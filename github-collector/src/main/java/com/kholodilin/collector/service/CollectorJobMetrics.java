package com.kholodilin.collector.service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-node accept counters. Controllers/services record; the configuration layer binds these values
 * to Micrometer meters. Replaces the former static counters.
 */
public final class CollectorJobMetrics {
  private final AtomicLong accepted = new AtomicLong();
  private final AtomicLong rejectedCapacity = new AtomicLong();

  public void recordAccepted() {
    accepted.incrementAndGet();
  }

  public void recordRejectedCapacity() {
    rejectedCapacity.incrementAndGet();
  }

  public long accepted() {
    return accepted.get();
  }

  public long rejectedCapacity() {
    return rejectedCapacity.get();
  }
}
