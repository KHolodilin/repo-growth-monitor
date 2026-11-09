# Assigned execution and result application (#215–#216)

Collector's execution loop is a local queue consumer, not a business scheduler. RGM supplies immutable
repository identity, date, parameters, configuration revision, authorization context and trusted destination
reference. STATS, TRAFFIC, REFERRERS, POPULAR_PATHS, GROWTH_EVENTS, SEARCH_QUERY, TOPIC_WATCH and explicit
DISCOVER_REPOSITORIES produce transport-only snapshots. No product datasource or product entity joins
are available in the Collector role.

Each actual GitHub request obtains local Bucket4j pacing and a durable RGM permit. Persisted bounded
observation checkpoints let retries resume without repeating completed reads; earliest source observation
time survives quota waits. The worker renews its queue lease on a separate heartbeat thread and guards
completion with owner/generation/live-lease predicates. Technical failures have bounded exponential
retries (five by default); quota waits defer without spending the failure count. FIFO due claims treat
Search and Topics equally; deferred projects do not block other due work. Responses and complete results
are bounded to 5MB persisted payloads, with projected issue/pull/release data and bounded pagination.
Oversized work fails with a sanitized split/narrow instruction rather than unbounded memory/DB growth.

Real zero remains different from an absent observation. Exact contributor count uses the last-page Link;
a truncated contributor login window is unknown, not complete. Optional missing files are absent health
observations. Missing/partial Search windows are not false negative ranks: absence requires a complete
window and separate confirmation. Basic ranks commit without waiting for cold competitor enrichment.

## Durable repository refresh

Admin + CSRF POST `/api/v1/admin/repositories/refresh` returns 201 and committed request/task IDs, one
discovery target per configured owner/credential. An active request receives one coalesced discovery task;
the normal source cadence is not independently planned by Collector. Discovery is bounded and only
applies repositories belonging to the explicitly assigned owner. It preserves tracking/history and never
marks other owners inaccessible. Configure a known repository's credential first. In orchestration mode,
legacy GET refresh is rejected with the new POST route and cached reads do not require a process GitHub
token. Legacy GitHub transport is disabled when the orchestration flag is active. Final Admin/public UI
caller migration belongs to the remaining dashboard work packages before activating the release.

## Results and delivery

Terminal result, immutable event ID and delivery outbox commit atomically in Collector PostgreSQL.
`collector.delivery.destinations` maps origin/reference to trusted HTTPS callback URLs, never user-supplied
URLs. Delivery uses the origin's node-to-RGM credential, runs outside DB transactions, accepts 204 after
commit, retries 408/429/5xx and transport failures, and dead-letters permanent rejects/exhausted retries.
Collection success and delivery success are separate: retrying delivery never collects GitHub again.
Delivered rows move to monthly history only after acknowledgment; dead letters remain retrievable.

```yaml
collector:
  execution:
    enabled: false
    lease: 1m
    poll-interval: 250ms
    request-timeout: 10s
    maximum-failures: 5
    maximum-pages: 5
    maximum-payload-bytes: 5242880
  delivery:
    enabled: false
    lease: 60s
    retry-delay: 5s
    poll-interval: 500ms
    maximum-attempts: 12
    destinations:
      - ref: rgm-results
        origin: rgm
        endpoint: https://rgm.example/internal/v1/collector-results
```

RGM authenticates the assigned node before replay, validates schema/job/attempt/source identity and payload
bounds, and uses released idempotency starter APPLY_COLLECTOR_RESULT in the product transaction. Permanent
unique attempt/event/result receipts protect correctness even after starter TTL cleanup. Conflicting reuse
returns 409. Request → task → attempt locking serializes concurrent completions and failover. Current
results atomically extend existing stats/traffic/health/growth/search/topic tables and terminal states;
superseded results receive an IGNORED receipt and 204 without changing snapshots. Database failure rolls
back business writes, receipt and starter record together. No historical rows are relocated.

`collection_history_mapping` relates new tasks to existing daily legacy runs. New `collector_snapshot_header`
also records successful empty source snapshots: cards stop displaying stale old rows, history retains the
empty date and the next nonempty source is first-seen rather than a delta from an older nonempty snapshot.
Logical relationships have no new physical foreign keys.

Authenticated recovery API: GET `/internal/v1/status`, GET `/internal/v1/jobs/by-attempt/{attemptId}`,
GET `/internal/v1/jobs/{collectorJobId}`, GET `/internal/v1/jobs/{collectorJobId}/result`,
POST `/internal/v1/jobs/{collectorJobId}/delivery/ack` with eventId, and POST
`/internal/v1/jobs/{collectorJobId}/delivery/retry`. Lookups are origin-scoped, including monthly archives.
Detached history retains permanent identity and cannot be mistaken for a new accepted job.
