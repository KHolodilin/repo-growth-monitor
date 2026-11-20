package com.kholodilin.collector;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * In-process queue and accept backpressure for a collector node.
 *
 * <p>Reject new accepts before the heap grows past these ceilings; RGM retries later. Byte sizes
 * are approximate (UTF-8 payload lengths of request/result bodies).
 */
@ConfigurationProperties("collector.capacity")
public record CapacityProperties(
    /**
     * Maximum jobs retained in memory across ready/running/terminal-awaiting-delivery. Default 100.
     */
    int maxJobs,
    /** Soft concurrency ceiling for RUNNING leases (metrics / gating). Default 4. */
    int maxInFlight,
    /** Approximate total payload bytes retained across queued jobs and results. Default 64 MiB. */
    long maxQueueBytes,
    /** Maximum single submission / JobRequest payload accepted. Default 256 KiB. */
    long maxRequestBytes,
    /** Fraction of jobs or bytes at which near-capacity metrics/health detail fire. Default 0.8. */
    double nearCapacityRatio) {
  public CapacityProperties {
    maxJobs = maxJobs == 0 ? 100 : maxJobs;
    maxInFlight = maxInFlight == 0 ? 4 : maxInFlight;
    maxQueueBytes = maxQueueBytes == 0 ? 64L * 1024 * 1024 : maxQueueBytes;
    maxRequestBytes = maxRequestBytes == 0 ? 256L * 1024 : maxRequestBytes;
    nearCapacityRatio = nearCapacityRatio == 0 ? 0.8 : nearCapacityRatio;
    if (maxJobs < 1
        || maxInFlight < 1
        || maxQueueBytes < 1024
        || maxRequestBytes < 256
        || nearCapacityRatio <= 0
        || nearCapacityRatio > 1)
      throw new IllegalArgumentException("Invalid collector capacity policy");
  }
}
