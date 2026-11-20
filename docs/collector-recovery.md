# Collector monitoring and recovery

RGM owns scheduling, health checks, reconciliation and assignment changes. Collector only executes accepted
work. Nodes are stateless across restarts (in-memory queue).

## Recovery sequence

1. Probe collector readiness (no PostgreSQL dependency on the collector node).
2. Reconcile lost acceptance ACKs by external attempt ID.
3. On terminal status, fetch result and apply the same ingestion as the callback path; confirm delivery.
4. A known terminal result is never recollected because callback transport failed.
5. Stuck ASSIGNED/ACCEPTED/RUNNING without progress beyond `rgm.orchestration.recovery.stalled-after`
   (default **25m**) may be reassigned to another eligible node in the same pool.

Dispatch `HTTP_RETRY` honors collector `Retry-After` (capacity 503) when scheduling the next attempt.

## Configuration

```yaml
rgm:
  orchestration:
    recovery:
      enabled: true
      stalled-after: 25m
      node-interval: 30s
      failure-threshold: 3
```
