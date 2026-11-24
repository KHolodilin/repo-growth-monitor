# Collector idempotency

Collector acceptance still uses the published idempotency starter. On the standalone node the persistence
adapter is **in-memory** (`InMemoryPersistenceStore`) with `TransactionContext.IMMEDIATE` — no collector
PostgreSQL and no JDBC transaction wrapper.

RGM product idempotency remains JDBC-backed on the product database.

## Accept path

1. Authenticate the RGM service token and validate the job envelope.
2. Scope `Idempotency-Key` with the trusted origin.
3. Starter acquire/fingerprint/replay or conflict.
4. `JobQueue.accept` stores the attempt in the in-memory queue (or replays the same public id).
5. Capacity full / oversized request / shutdown → `503` + `Retry-After` + `CAPACITY` (no durable accept).

After a process restart the in-memory idempotency map and queue are empty. RGM re-dispatches with a new
or repeated attempt according to orchestration policy.

## Configuration

```yaml
collector:
  runtime:
    enabled: true
  idempotency:
    retention: 7d
  acceptance:
    destinations: rgm-results
  http:
    enabled: true
    clients:
      - origin: rgm
        token: ${COLLECTOR_RGM_SERVICE_TOKEN}
```

Prefer the standalone `github-collector` boot JAR (`Dockerfile.collector`). Combined-process profiles remain
a local/dev bridge only.
