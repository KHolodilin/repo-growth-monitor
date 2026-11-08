# Secure GitHub credentials and Collector caching (#213)

## Ownership and activation

RGM owns encrypted credentials and repository authorization. Collector receives a secret only from an
authenticated internal resolve call for its assigned job/attempt. GitHub tokens never enter job envelopes,
dispatch/Collector queues, result/outbox payloads or credential exception messages. Service credentials
authenticate RGM ↔ Collector independently from GitHub credentials. The existing legacy GITHUB_TOKEN is
not automatically copied into the new vault, and legacy GitHub execution remains unchanged by default.
GitHubProperties, credential leases, rotation input, encrypted payload and configuration toString redact secrets.

Flags default off: `rgm.credentials.enabled` enables the RGM vault/API; `collector.credentials.enabled`
enables the Collector resolver/cache. Orchestration/storage/HTTP flags from earlier steps still apply.
See [external YAML example](../deploy/collector-credentials.example.yml). No intermediate deployment is performed.
Actual assigned task execution and the common limiter are #215 and #214 respectively.

## Product database and encryption

Migration 016 is additive, contains no physical FK and does not move or edit old collection/history rows:

| Table/change | Fields and indexes |
|---|---|
| github_credential | BIGSERIAL PK, unique ref, current_version, revoked, updated_at |
| github_credential_version | BIGSERIAL PK, credential_id/version unique, key_id, 12-byte nonce, ciphertext, expiry/timestamps |
| repository_credential | repository_id PK, credential_id index, unique authorization_context UUID, timestamp |
| collection_task | nullable authorization_context UUID and credential_ref for frozen assignment scope |

AES-256-GCM uses a random 96-bit nonce for every write and a 128-bit authentication tag. Authenticated
metadata binds ciphertext to credential ref, version and encryption key ID, preventing row/version swaps.
The encryption keyring is supplied outside the database: `rgm.credentials.encryption-keys.<key-id>` contains
a Base64-encoded 32-byte key, with `active-key-id` selecting new writes. The YAML example uses an external
environment secret. No default key exists; invalid keys fail startup. Keep the keyring separate from database
backups and retain old key IDs while any stored version/backups need them. Changing active key ID affects new
writes; rotating a credential re-encrypts its new version under the active key. Missing keys/tampering fail closed.

Credential rotation locks the ref, increments its version and writes ciphertext atomically. Binding a repository
issues a fresh UUID authorization context, including when reauthorizing the same credential ref. Rebinding
invalidates previous task authorization; token rotation preserves context so existing assignments can resolve
the current version. Revocation blocks future resolutions and new task creation through that credential.

## Administrative operations

All operations use existing Admin session authorization and CSRF. Responses contain metadata only.

- `PUT /api/v1/admin/credentials/{ref}` with `{ "token": "<secret>", "expiresAt": "<optional ISO instant>" }`
  creates/rotates a version and returns ref/version/revoked/expiry. Ref uses ASCII letters, digits, `_`, `-`.
- `PUT /api/v1/admin/repositories/{id}/credential` with `{ "ref": "github-main" }` binds a repository and
  returns nonsecret AuthorizationScope (context/ref).
- `DELETE /api/v1/admin/credentials/{ref}` revokes future resolution (204; 404 for an unknown ref).

Encryption settings must be installed before vault activation. Create credentials and bindings before enabling
new collection triggers. An unbound/revoked repository fails creation with 503 and rolls back the invocation.
Existing pre-credential tasks have no scope and cannot obtain credentials; reconcile/recreate those assignments
as part of the final activation rather than guessing authorization or importing historical jobs.

## Node-authenticated resolution

`POST /internal/v1/credentials/resolve` accepts only `CredentialRequest(schemaVersion,jobId,attemptId)`.
RGM authenticates the bearer token against enabled configured nodes and derives the node key from that
identity. Each node has a unique `rgm.orchestration.nodes[].resolve-token` (at least 32 characters), configured
as the matching Collector origin's `service-token`. Browser sessions, caller-provided node headers and the
opposite-direction acceptance service token do not authorize resolution. The dedicated route matcher is
exact so it does not intercept Collector's origin-authenticated invalidation route in combined mode.

The query verifies job/attempt identity, current_attempt_id, assigned node, active states, current repository
binding/context/ref, nonrevoked credential and current version. Assignment/authorization rows are shared-locked
during this short transaction. There is no GitHub/network call in the transaction. Wrong node, superseded or
expired assignment, expired token and stale authorization context return 403; invalid service credentials 401.
Tampering or unavailable encryption material returns a sanitized 503. Success uses Cache-Control: no-store.

The response is CredentialLease: authorizationContext, credentialRef/version, token, actual expiry and
cacheValidUntil. Lease validity is min(server cache lease, actual token expiry, assignment deadline).
Persisted expiry/deadline instants are floored to PostgreSQL microsecond precision, so rounding cannot extend
authority beyond the original deadline. The shared JobRequest carries only the nonsecret AuthorizationScope;
version/secret is resolved dynamically. Internal queue leases preserve the authenticated RGM origin for execution.

## Caffeine behavior on each VPS

Each Collector has its own independent bounded Caffeine caches:

1. Authorization mapping key: RGM origin + GitHub repository ID + authorization context + credential ref.
2. Secret key: RGM origin + authorization context + credential ref + credential version.

Repository names and owner login are not credential identity. Separate origins/contexts cannot share secrets.
The mapping contains no token. Multiple tasks with the same trusted project scope reuse one resolve result;
different contexts reauthorize separately. Concurrent cache loads are single-flight. Losing/evicting a secret
causes an authenticated reload, including when Caffeine immediately rejects a cold entry under capacity pressure.
No database is shared by worker nodes and no secret is transferred from A's cache to B's cache on failover:
RGM reassigns the task; B uses its own service identity to resolve the current credential into an empty cache.

Defaults, all configurable by YAML/relaxed environment binding:

| Setting | Default |
|---|---|
| collector.credentials.ttl | 2h after write |
| collector.credentials.maximum-size | 100 for each cache |
| collector.credentials.maximum-rejected | 1000 combined version/context quarantine entries |
| collector.credentials.failure-backoff | 5s negative resolution caching |
| collector.credentials.connect-timeout / read-timeout | 3s / 10s |
| rgm.credentials.cache-lease | 2h maximum issued lease |

Expiry is min(local TTL, issued lease, actual token expiry, original job deadline). Reads do not extend it.
The default 30-minute orchestration job timeout can therefore cap an issued lease below the configured two
hours. Failed resolution has a short shared negative-cache window and never serves an expired cached secret.
Per-entry expiry uses a monotonic Caffeine ticker plus wall-clock lease validation; tests inject both clocks.
No expire-after-access extension is used.

On GitHub 401, CredentialedGitHubRequest quarantines that exact rejected origin/context/ref/version,
single-flight resolves current credentials and retries the request **at most once**. Every request, including
the retry, must obtain a RequestGate permit. No bypass gate bean is supplied; #214 implements the shared
limiter and #215 supplies this pipeline to actual collectors. QuotaKey uses origin/ref so token rotation does
not create a fresh quota budget. GitHub 403/429 do not invalidate or resolve credentials. Other exceptions
are sanitized before leaving the request primitive.

If RGM returns the same rejected version, it is not sent to GitHub again. A second 401 quarantines the new
version and ends that operation without a third resolve/request. A delayed 401 for an old version cannot
evict newer entries. Quarantine metadata contains no secrets and is bounded: reaching the configured limit
fails the cache closed rather than evicting knowledge of rejected credentials. Correct configuration/versions
and recreate the process/cache to clear this process-level safety state.

## Revocation and lease boundary

`POST /internal/v1/credentials/invalidate` on Collector with `{ "context": "<UUID>" }` uses the existing
RGM-origin acceptance bearer credential. It evicts that origin's mapping/secrets and quarantines the context;
other origins/contexts are unaffected. A fresh repository binding/context is required for future work.
This is explicit per-node service invalidation; no automatic cross-VPS fanout or message broker is introduced.

RGM revocation/reassignment denies new resolve requests immediately. Already issued local leases remain
usable until their cap unless that Collector receives explicit invalidation or GitHub rejects/revokes the token.
For immediate removal, revoke the token at GitHub and/or invalidate the context on every affected Collector;
an in-flight request cannot be recalled. Lease expiry is the bound for an unreachable node. Automatic recovery,
reconciliation/fanout and assignment fencing remain #217. Never assume that a database revocation instantly
clears memory on another VPS.

## Targeted validation

Fake-clock tests cover two-hour write TTL, token/lease caps, bounds/eviction, single-flight load/refresh,
authorization isolation, rejected same-version behavior, stale failures, 403/429, RGM outage and cold-cache B.
Real HTTP tests verify configured authority, service header and disabled redirects. PostgreSQL tests cover
encrypted-at-rest data, authenticated assigned-node resolution, rotation/rebind/revocation, expiry, failover,
tampering and Admin/CSRF. Standalone Collector bootstrap exercises the authenticated invalidation route.
Existing protocol, queue, idempotency, orchestration and role tests remain targeted regressions. Full-suite
95% coverage, Codecov and complete local release/migration rehearsal stay at the final work package.
