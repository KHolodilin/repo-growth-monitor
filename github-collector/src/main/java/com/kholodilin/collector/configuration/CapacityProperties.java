package com.kholodilin.collector.configuration;

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
    /** Approximate total payload bytes retained across queued jobs and results. Default 64 MiB. */
    long maxQueueBytes,
    /** Maximum single submission / JobRequest payload accepted. Default 256 KiB. */
    long maxRequestBytes) {
  public CapacityProperties {
    maxJobs = maxJobs == 0 ? 100 : maxJobs;
    maxQueueBytes = maxQueueBytes == 0 ? 64L * 1024 * 1024 : maxQueueBytes;
    maxRequestBytes = maxRequestBytes == 0 ? 256L * 1024 : maxRequestBytes;
    if (maxJobs < 1 || maxQueueBytes < 1024 || maxRequestBytes < 256)
      throw new IllegalArgumentException("Invalid collector capacity policy");
  }
}
