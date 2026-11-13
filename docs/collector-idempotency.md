# Starter-backed request and acceptance idempotency (#212)

## Dependency and ownership

The Maven dependency is **com.kholodilin:spring-boot-idempotency-starter:1.2.0**, pinned in the root POM.
This is the released Boot 4/JDBC/Jackson 3 starter, not copied implementation or a locally installed SNAPSHOT.
Release availability was checked against [Maven Central metadata](https://repo.maven.apache.org/maven2/com/kholodilin/spring-boot-idempotency-starter/maven-metadata.xml)
and [v1.2.0](https://github.com/KHolodilin/spring-boot-idempotency-starter/releases/tag/v1.2.0).
The resolved starter JAR matches Maven Central SHA-1 `b43f73663e629f55109529ead81fb64f0178edeb`.
Only its canonical DDL is reproduced in Liquibase, as instructed by the starter's JDBC schema API.
Acquisition, concurrent arbitration, canonical fingerprinting, result serialization, replay, TTL metadata
and conflict detection are executed by the dependency's classes.

Generic starter auto-configurations are explicitly excluded in the executable application: one inferred
datasource/service is unsafe when combined mode has two databases. Explicit qualified pairs are wired:

| Scope | Service | JDBC store | Transaction resource |
|---|---|---|---|
| RGM | rgmIdempotency | rgmIdempotencyStore | product DataSource / product manager |
| Collector | collectorIdempotency | collectorIdempotencyStore | collectorDataSource / collectorTransactionManager |

Both use JdbcPersistenceStore, CanonicalJsonFingerprintStrategy and JacksonIdempotencySerializer from
the published starter. Both local and distributed replay caches are absent. Credential Caffeine caching
is independent and arrives in #213. The `DataSourceTransactionContext` wiring adapter probes the exact
JDBC resource's existing transaction state; an unrelated active transaction, or a connection merely
bound to synchronization, cannot authorize persistence. It supplies no replay/acquisition implementation.

Product migration 015 creates the canonical idempotency_records table. Collector migration 001 already
owns its separate copy. Each qualified store runs JdbcSchemaManager in VALIDATE mode after database
initialization; application beans never create these schemas or point at a fallback product datasource.
The existing RGM collection/history tables are untouched.

## Manual collection API

`POST /api/v1/admin/repositories/{id}/collections` now requires `Idempotency-Key` (1–128 ASCII token
characters: letters, digits, `.`, `_`, `:`, `-`). The endpoint has no body. Session Admin authorization
and CSRF run before the handler, and IdempotentCollectionRequests rechecks the current account/role
before any replay lookup. The trusted actor is the internal user ID from the authenticated principal.
Operation is `CREATE_COLLECTION_REQUEST`; the effective key is a SHA-256 canonical fingerprint of
trusted actor scope plus client key. Client-supplied actor/origin headers are not used.

The business fingerprint contains the requested repository ID. Server-generated business date, UUIDs
and current settings are not client payload: retries across midnight/config changes replay the original
requestId/taskIds/coalesced outcome. Same actor/key targeting a different repository returns 409;
another actor has a distinct key namespace. Missing/invalid keys return 400. Authorization revocation
returns 403 even when a success was previously recorded. The response remains 201 after commit.

In one product transaction the starter acquires the key, the existing creation service freezes and
creates/coalesces work, business identity is bound, and the starter persists the accepted outcome.
Any exception rolls back idempotency row, invocation, tasks, assignments, dispatch and binding together.
Scheduled creation still uses its permanent scheduled trigger key, through the same core creation service.

Migration 015 also adds `collection_request_command`: command_key PK, actor_id, repository_id,
request_id, coalesced and accepted_at, with a request_id index and logical relationships (no FK).
This is a compact permanent **command-to-request identity**, including aliases when multiple manual
keys coalesce into one active request. It stores no HTTP result payload, replay status or execution engine.
If temporal starter rows are cleaned up, the starter's action resolves this business identity and returns
the original task IDs; it cannot recollect a completed request or silently change the command target.
Retain bindings and their request/task identities together indefinitely unless an explicit future policy changes this guarantee.

## Collector acceptance API and service identity

`POST /internal/v1/jobs` accepts the versioned JobRequest and requires:

- `Authorization: Bearer <configured service token>`;
- `Idempotency-Key: <attemptId>` matching the explicit assignment;
- a known destination reference (default `rgm-results`), never an arbitrary callback URL.

A stateless dedicated filter chain authenticates before JSON decoding and every replay. The token maps
to a trusted configured origin; product browser sessions and caller-provided origin headers grant no access.
Tokens are compared without an early-return string comparison and are redacted from configuration toString.
Cookies/CSRF are not used for this bearer-authenticated internal route. Product API CSRF remains enabled.
Service credentials authenticate RGM and are distinct from GitHub tokens, which are not in these jobs.

Operation is `ACCEPT_COLLECTOR_JOB`, scoped by authenticated origin and key. One Collector transaction
contains the starter acquire, permanent attempt identity, local queue insert and saved JobAccepted result.
201 is returned only after commit. Concurrent identical requests and lost-response retries return the
same collectorJobId/jobId/attemptId/acceptedAt. A changed valid envelope for an accepted key returns 409.
Malformed envelopes/version/key/destination return 400, invalid credentials 401, unavailable durable
storage 503. No GitHub call, execution loop or callback network operation runs in this transaction.

The unpartitioned `(origin_id,external_attempt_id)` identity remains authoritative after the job is archived
or a history partition detached. Accepted timestamp is retained with public ID. After starter cleanup,
the same attempt replays its original acceptance and changed attempt content conflicts; no new job executes.
201's ACCEPTED status acknowledges the command, rather than asserting that a previously accepted job is still queued.

## Configuration and standalone HTTP role

Existing combined mode remains opt-in for new orchestration/storage. To expose Collector HTTP in a combined
process, set `collector.storage.enabled=true` and `collector.http.enabled=true`, provide the Collector datasource
and configure the clients below. To run only Collector from the **same image/JAR**, use profiles
`collector,collector-http`; the second profile enables servlet acceptance/storage while product scanning,
product datasource, user accounts and planners stay disabled. The headless `collector` profile still works
for the original bootstrap checks. These are acceptance capabilities; full execution/delivery remains #213–#217.

```yaml
collector:
  idempotency:
    retention: 7d
  acceptance:
    destinations: rgm-results
  http:
    enabled: true
    clients:
      - origin: rgm
        token: ${COLLECTOR_RGM_SERVICE_TOKEN}
rgm:
  orchestration:
    idempotency-retention: 7d
```

Provide the same per-node service token in RGM's `rgm.orchestration.nodes[].service-token` and the corresponding
Collector client entry. Tokens require at least 32 characters; no default secret is supplied. Multiple trusted
RGM origins may be configured with unique IDs/tokens. `collector,collector-http` uses `COLLECTOR_RGM_ORIGIN`
(default rgm) and required `COLLECTOR_RGM_SERVICE_TOKEN`; datasource environment variables are unchanged.
Use protected HTTPS/service networking between VPS nodes as in the routing design.

Retention settings control starter expires_at metadata, with a default of seven days per store. The starter
keeps rows replayable until **physical deletion**, even after expires_at passes. Automatic generic cleanup
is disabled, avoiding accidental cross-datasource cleanup. Operators may use the starter's
JdbcIdempotencyPersistenceCleanup for a specifically selected datasource and bounded batches. Cleanup skips
locked/in-flight rows and does not touch either permanent business identity registry. History retention is independent.

## Verification

Targeted tests use the real 1.2.0 dependency and separate PostgreSQL 18 databases in combined mode.
They cover concurrent duplicates in both stores, actor/origin namespaces, payload conflicts, revoked
authority, stable lost-response replay, transactional failure/rollback, archive + physical replay cleanup,
retained coalesced aliases, and rejection when only the other datasource owns an active transaction.
Standalone Collector HTTP bootstrap verifies acceptance without product DB/users/controllers and denies
product routes. Queue, orchestration, runtime-role and MVC regression checks remain in the targeted run.
Full suites/95% coverage/Codecov and complete release/migration rehearsal remain the final work package.
