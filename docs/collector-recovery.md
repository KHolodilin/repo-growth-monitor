# Collector monitoring and independent-node recovery (#217)

RGM owns scheduling, health checks, reconciliation and assignment changes. Collector only executes explicitly
accepted work. Every node may have its own VPS, PostgreSQL database and empty credential cache.

## Recovery sequence

```mermaid
sequenceDiagram
 participant R as RGM monitor
 participant DB as Product PostgreSQL
 participant A as Collector A / DB A
 participant B as Collector B / DB B
 R->>A: authenticated status / by-attempt lookup
 A--xR: repeated transport failures
 R->>B: authenticated readiness check
 B-->>R: execution enabled
 R->>DB: lock request, task and current attempt
 Note over R,DB: minimum age, repeated failures, same pool, attempt limit
 R->>DB: supersede A, create attempt B + dispatch, stable jobId
 R->>B: POST job (new attemptId)
 B->>R: resolve current assignment credential
 R-->>B: bounded lease, current version and shared quota identity
 B->>R: acquire shared GitHub request permit
 Note over R,DB: existing quota/cooldown survives reassignment
 B->>B: execute, checkpoint, persist result + delivery
 B->>R: authenticated immutable result callback
 R->>DB: current-attempt check + history + receipt + completion
 R-->>B: 204 after commit
 A->>R: late result for superseded attempt
 R->>DB: receipt outcome IGNORED; no history overwrite
 R-->>A: 204
```

An unreachable node may still be executing. Reassignment fences its new credential resolution and every
new GitHub permit: even a cached GitHub token cannot bypass the RGM budget gate. Already in-flight HTTP
cannot be recalled. The current attempt is rechecked under the same request/task lock order as callback
application. A concurrent callback either commits first and prevents reassignment, or becomes IGNORED.
Stale dispatch acknowledgments and expired local execution/delivery leases cannot change the winner.

The monitor first reconciles lost acceptance ACKs by external attempt ID. A terminal status triggers
result retrieval and the same idempotent ingestion used by callbacks, followed by recipient delivery ACK.
**A known terminal result is never recollected because its callback/result transport failed.** It remains
RESULT_UNAVAILABLE in Admin diagnostics for retry/operator recovery, including detached history.
Delivery dead letters are retrievable and can be explicitly requeued. No automatic data deletion occurs.

## Configuration

```yaml
rgm:
  orchestration:
    enabled: false # activate only as part of the completed release
    job-timeout: 2h
    recovery:
      enabled: false
      tick-interval: 1000
      node-interval: 30s
      minimum-age: 2m
      stalled-after: 10m
      failure-threshold: 3
      batch-size: 20
      maximum-attempts: 3
```

Node probes use persisted generation-fenced leases, so multiple RGM instances do not all probe at once.
Only enabled, ready, recently successful nodes in the configured owner/repository pool receive new work.
One failed probe is insufficient. RUNNING heartbeats count as progress; queued quota retries remain valid
until assignment deadline. Exhausting the configured assignment count fails the task, blocks its old
dispatch and closes the request when all other tasks finish. With no healthy alternate node, work waits
for a healthy route rather than asserting that the original process stopped.

GET `/api/v1/admin/collectors` exposes health/failure counts and unresolved attempt age/state without
tokens, payloads or secret endpoints. Collector `/internal/v1/status` exposes pending count, oldest queue
age and dead delivery count scoped to the authenticated origin. HTTP uses configured endpoints, separate
directional service credentials, bounded connect/read timeouts and no redirects. Network calls occur
outside product transactions.

## Measured 100-repository scenario

The repeatable tests use local PostgreSQL 18.6 and the real task expansion/queue/source adapter paths;
GitHub responses are fixtures, not live GitHub calls. This is not a production VPS throughput benchmark.

| Input / measurement | Result |
|---|---|
| Repositories / queries / topic-language watches | 100 / 100 / 200 |
| Explicit base jobs | 800 |
| Core / GraphQL / Search calls | 2500 / 200 / 600 |
| Search assumption | tracked repo absent, independent confirmation for each rank |
| Queue acceptance and first mixed claims, unpooled JDBC test | about 15.4 seconds |
| Oldest pending age at measurement | 15 seconds |
| Connections held per queue transaction | one; none retained during source/callback HTTP |
| Quota waiting | deferred due time; no technical failure increment |

At defaults (Core/GraphQL 3000/hour, Search 20/minute, one in-flight request per principal), pacing alone
requires roughly 50 + 4 + 30 = **84 minutes**, before latency, retries and optional full pagination. Reserves
and actual response headers can lengthen this. Hence a 2h default deadline and a **daily** starting cadence
are appropriate for this scenario; hourly scheduling is insufficient. More configured queries/watches or
full five-page windows must be budgeted separately. Multiple workers using the same principal do not
multiply quota. Distinct verified quota identities may have independent budgets.

Cold basic ranks do not synchronously enrich competitors: the measured Search response contains an
uncached competitor, rank is committed with PARTIAL/DEFERRED enrichment and performs no fan-out GitHub
calls. Full competitor enrichment is not included in the 84-minute estimate. For 100 queries and 200
watches with 50 distinct results each, a cold fan-out could reach 15000 targets before deduplication;
any future enrichment cadence must account for that separately rather than silently consuming the rank
budget. Failed source attempts retry at most five times; quota deferrals do not spend those attempts.

## Targeted failure verification

- Independent Collector databases contain no product repository tables and do not see each other's attempts.
- Repeated node partition, healthy B, minimum-age/attempt policy and same-pool assignment fencing.
- Lost acceptance ACK/status reconciliation and terminal-result pull without another collection.
- Lost callback ACK over real HTTP, stable repeated bytes/event ID, retry from reconstructed sender state.
- Late A result before/after B, concurrent failover/callback and rejected stale credential resolution.
- Reconstructed RGM monitor/budget objects retain durable assignment/receipt/cooldown state after restart.
- PostgreSQL rollback injection, lease generation fencing, dead-letter recovery and isolated origin lookups.
- Standalone Collector Spring role starts execution and delivery without a product datasource.

The complete release-wide suite, coverage to 95%, CI/Codecov review and full local Docker/migration
rehearsal remain the agreed final gate after all issues. No intermediate release/deployment is performed.
