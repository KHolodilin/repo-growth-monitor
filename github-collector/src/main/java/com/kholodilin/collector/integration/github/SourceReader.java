package com.kholodilin.collector.integration.github;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.repository.JobQueue.Lease;
import tools.jackson.databind.JsonNode;

public interface SourceReader {
  JsonNode read(
      Lease lease,
      JobRequest job,
      String key,
      String method,
      String path,
      String body,
      boolean optional);

  void invalidate(Lease lease, String key);

  default java.time.Instant observedAt(Lease lease) {
    return java.time.Instant.now();
  }
}
