# Collector boundary and protocol

## Build and roles

| Artifact | Role |
|---|---|
| `collector-contracts` | Plain Java wire DTOs |
| `github-collector` | Standalone Boot app (`GitHubCollectorApplication`) |
| `backend` | Product UI, planner, orchestration, credential vault (no GitHub product fetches) |

Build: `mvn -B package`. Collector tests: `mvn -B -pl github-collector -am test`. Backend: `mvn -B -pl backend -am test`.

Standalone collector image: `Dockerfile.collector` → `github-collector-*-boot.jar`. No collector PostgreSQL.
Queue, observations and acceptance idempotency are process-local. RGM owns schedules, retries and history.

The product process does **not** embed collector execution or GitHub integration. Combined-process
`collector` profiles were removed from backend; run the standalone collector JAR / image only.

## Wire endpoints

| Endpoint | Auth | Contract |
|---|---|---|
| POST /internal/v1/jobs | RGM service token | JobRequest → 201 JobAccepted; capacity → 503 CAPACITY + Retry-After |
| GET /internal/v1/jobs/{id} | originating service | JobStatus |
| GET /internal/v1/jobs/{id}/result | originating service | terminal ResultDelivery |
| POST /internal/v1/collector-results | collector → RGM | ResultDelivery → 204 |
| POST /internal/v1/credentials/resolve | collector → RGM | CredentialRequest → CredentialLease |

## Ownership

RGM creates tasks/assignments/dispatch. Collector executes accepted work, checkpoints in memory, delivers
terminal results. Restarts drop local state; RGM reconcile (~25m stalled default) re-assigns.
