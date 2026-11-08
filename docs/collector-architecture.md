# Collector boundary and protocol v1 (#209)

## Build and roles

One reactor, one image, one combined process remains the default. `collector-contracts` is a plain Java JAR (no Spring/Jackson runtime dependency), `github-collector` is the execution-module boundary, `backend` owns product/orchestration and the executable Boot JAR. Build from the root: `mvn -B package`; isolated collector compilation/tests: `mvn -B -pl github-collector -am test`. Backend with dependencies: `mvn -B -pl backend -am test`. Do not use a standalone backend build without first installing its reactor dependencies.

Default preserves the existing application, including legacy collection. `--spring.profiles.active=web-only` excludes legacy worker pools, disables scheduling/manual planning and blocks GitHub network calls at the client guard, even if a token is configured. Product DB/auth are still required. Legacy operations requiring GitHub will explicitly fail in this transitional role until durable dispatch is implemented; this is not yet a completed production split.

`--spring.profiles.active=collector` starts the same image headlessly, without product components, authentication, planners or the old datasource. For #209 this loads only the execution boundary: it DOES NOT accept jobs or collect data yet. #210/#212 add its own datasource/durable acceptance. No fake 202 endpoint exists. No public HTTP/Actuator listener is promised in this headless bootstrap. Scheduling belongs to RGM, never Collector. Do not disable legacy execution in production until the replacement path is ready. Profiles are startup-only; changing them requires restart.

## Wire endpoints (specified, not implemented in #209)

| Endpoint | Authentication | Contract |
|---|---|---|
| POST /api/v1/admin/repositories/{id}/collections | Admin + CSRF | types -> 202 requestId + tasks after product commit |
| POST /internal/v1/jobs | RGM service identity | JobRequest -> 202 JobAccepted + Location after collector commit |
| GET /internal/v1/jobs/{collectorJobId} | originating service identity | JobStatus |
| GET /internal/v1/jobs/{collectorJobId}/result | originating service identity | terminal ResultDelivery or explicit not-ready |
| POST /internal/v1/collector-results | assigned collector identity | ResultDelivery -> 204 after product transaction |
| POST /internal/v1/credentials/resolve | assigned collector identity | CredentialRequest -> CredentialLease |

Only configured base URLs/destination references are used; no caller-provided callback URL or secret in the job. URI, database endpoints, TLS and service authentication remain deployment configuration; cross-VPS access must use authenticated protected networking. GitHub ID is a decimal string to avoid JavaScript precision loss. UUIDs identify protocol resources, BIGINTs remain private database PKs.

`CollectorProtocol` defines immutable records, task/status/error enums, supported schemaVersion=1 and typed observation values. Unsupported versions/enum values are rejected at the boundary. Missing observations remain absent/null; empty contributorLogins means a collected empty list, null means not collected. All collection fields are observational, not publication authority. Expanding the envelope or payload requires version/compatibility tests; #215 finalizes source-specific field coverage before enabling tasks. HTTP adapters map validation to 400/422, conflicts to 409, auth to 401/403 and unavailable durable storage to 503. Never map a durable acceptance to success before commit.

## Lifecycle and ownership

requestId = user/scheduled invocation; jobId = one concrete task; attemptId = assignment/failover generation; collectorJobId = local accepted job; eventId/resultId = immutable terminal delivery/data. POST retry uses the same attempt/idempotency key and exact immutable business payload. A failover creates a new attempt, not a new task. RGM fences old attempts before applying snapshots. Job execution success and result delivery success are different states.

RGM creates tasks/assignments/dispatch atomically. Collector owns its queue/executions/results/callback outbox. RGM owns existing snapshots/history and applies callback plus receipt atomically. Existing tables/rows stay in the old database, with no historical relocation. There are no cross-database FK or distributed transactions. The idempotency starter, Bucket4j, Caffeine credentials, databases and actual routing are subsequent issues, not invented implementations here.

## Inventory of existing coupling to remove in #210–#217

| Existing code | Current dependency / next boundary |
|---|---|
| DailyPlanner / CollectionPlanner | product schedule and shared run/job creation; schedule stays in RGM, execution dispatch becomes durable |
| CollectionWorkerPool / SearchWorkerPool | ApplicationReadyEvent loops; currently use product DB, guarded off in web-only; replaced per migrated scope |
| SearchWorkerPool | searchWorker.poll || topicWorker.poll favors Search; fairness required in #215 |
| CollectionWorker / SearchWorker / TopicWorker | product repository/owner lookups, advisory lock, leases and writes; replace with explicit task inputs and own queue |
| RepositoryStatsCollector / TrafficCollector / ReferrerCollector / PopularPathsCollector / GrowthEventsCollector | GitHub calls plus product writes; split observation production from RGM application |
| RepositoryService.list(refresh), RepositoryController | synchronous token-accessible repo discovery; change to durable refresh command |
| CollectionController / SearchQueryController / TopicVisibilityController | manual triggers use old planners; translate to RGM orchestration, preserve Admin operations |
| TopicVisibilityService.visibility/historyByTopic | GET calls TopicWatchSync; remove public read side effects |
| CompetitorCardService.fetchAndStore / RepositoryEnricher | live enrichment and cache writes; move explicit collection work behind contracts |
| GrowthEventService / AnalyticsService / TrafficJdbcRepository | product interpretation/history reads remain in RGM |
| MockUserSyncRunner | product auth startup only; absent from collector role |
| RuntimeGrantsRunner / MigrateExitRunner | product migration CLI only; absent from collector role, no cross-DB migrations |

Legacy DailyPlanner/manual planning and worker pools remain fully enabled in default combined mode. No job is silently sent to both paths. Shared GitHub token state is currently the legacy client; independent credentials/cache/limiting will be implemented in #213/#214. CollectorModuleConfiguration deliberately contains no scheduler or product dependency.

## Validation and release workflow

For #209 run contract JSON roundtrips, identity/version/immutability checks and role-bootstrap/transport guard tests, plus a reactor package build. Run representative existing controller/client/planner tests to catch component-scan regressions. Full suite/95% overall coverage, Codecov review, migrations and full Docker release rehearsal remain the final gate after #222 as explicitly agreed by the user. One branch/one Draft PR covers #209–#222, issue-scoped commits, no intermediate merge/tag/release. Do not claim independent production collector execution before the later issues deliver queue, credentials, collection and callbacks.
