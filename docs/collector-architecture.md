# Collector boundary and protocol

## Build and roles

| Artifact | Role |
|---|---|
| `collector-contracts` | Plain Java wire DTOs |
| `github-collector` | Standalone Boot app (`GitHubCollectorApplication`) |
| `backend` | Product UI, planner, orchestration, credential vault (no GitHub product fetches) |

Build all (default profile): `mvn -B package`. Collector only: `mvn -B -Pcollector,!full test`. Backend only: `mvn -B -Pbackend,!full test`. From a full checkout, `-pl … -am` still works under `-Pfull`.

Standalone collector image: `Dockerfile.collector` → `github-collector-*-boot.jar` (`-Pcollector,!full`, no backend sources). Product image: `Dockerfile` (`-Pbackend,!full`, no github-collector sources). No collector PostgreSQL.
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
