package com.kholodilin.collector.service;

import com.kholodilin.collector.contracts.CollectorProtocol.JobRequest;
import com.kholodilin.collector.integration.github.GitHubSourceReader;
import com.kholodilin.collector.model.CollectionContext;
import com.kholodilin.collector.repository.ObservationStore;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Service-owned per-observation checkpoints: reuse, persist and invalidate before GitHub HTTP on a
 * miss.
 */
@RequiredArgsConstructor
public final class ObservationCheckpoint {
  private final ObservationStore observations;
  private final GitHubSourceReader github;
  private final JsonMapper json;

  public JsonNode read(
      CollectionContext context,
      JobRequest job,
      String key,
      String method,
      String path,
      String body,
      boolean optional) {
    Objects.requireNonNull(context);
    var stored = observations.find(context.observationJobId(), key);
    if (stored.isPresent()) return json.readTree(stored.get());
    JsonNode observation =
        github.fetch(context.trustedOrigin(), job, key, method, path, body, optional);
    observations.save(context.observationJobId(), key, json.writeValueAsString(observation));
    return observation;
  }

  public void invalidate(CollectionContext context, String key) {
    observations.remove(context.observationJobId(), key);
  }

  public Instant observedAt(CollectionContext context) {
    return observations.firstObserved(context.observationJobId());
  }
}
