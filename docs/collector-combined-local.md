# Local split RGM / Collector runtime

The repository-root Compose stack runs **two application containers from the same image**, matching the
production role split:

| Service | Role |
|---|---|
| `repo-growth-monitor` | Product UI/API, planner, durable orchestration, credential vault, result ingestion (`rgm.collector.enabled=false`) |
| `collector` | Profiles `collector,collector-http`: job acceptance, GitHub execution, result delivery |

They talk over the Compose network (`http://repo-growth-monitor:8080` ↔ `http://collector:8080`) with
service tokens. Host port `8080` is only the RGM entry. Collector HTTP stays internal.

PostgreSQL still hosts both databases in one instance: `repogrowth` (product) and `collector` (queue /
results / history) with the dedicated `collector` role. The root `docker-compose.override.yml` mounts
split runtime YAML from `deploy/tmp/`. The release pack in `deploy/docker-compose.yml` follows the same
two-role layout and is not a production VPS activation.

## Starting a new local checkout

Configure the existing `.env` GitHub token, then run from the repository root:

```sh
python deploy/prepare-combined-local.py
docker compose up -d --build
# Wait until RGM answers on :8080, then import the development token through Admin APIs.
python deploy/bootstrap-combined-local.py
docker compose up -d --force-recreate repo-growth-monitor collector
```

Preparation starts PostgreSQL, provisions the Collector role and database, and writes:

- `deploy/tmp/rgm-runtime.local.yml`
- `deploy/tmp/collector-runtime.local.yml`

Both are gitignored. Encryption keys and service tokens are generated once and reused on reruns,
including migration from the older single-file `combined-runtime.local.yml` when present. Keep the
encryption configuration with database backups. Bootstrap is restricted to the local non-production
MOCK stack and never prints the GitHub token. Existing vault credentials are preserved; use `--rotate`
explicitly when intentionally replacing the local token.

No additional repository/board configuration UI is introduced. Existing repository selection continues
to control collection. `rgm.credentials.default-ref` lets a newly selected, unassigned repository use the
configured default credential. First-use binding is serialized on the product repository row. An explicit
binding, including a revoked credential, is never replaced by the default. The token stays encrypted in
RGM, is resolved for an assigned task, and is cached by Collector for the configured 2h lease.

The planner is initially disabled while credentials are configured; bootstrap enables it and asks for a
recreate of both app services. RGM owns all schedules and task creation. Collector only executes assigned
jobs. Legacy product worker pools/transport stay disabled in orchestration mode. Existing rate-budget and
idempotency policies apply.

## Verified local smoke

Re-check after the split:

1. `docker compose ps` shows `postgres`, `repo-growth-monitor`, and `collector` up.
2. RGM serves public/owner pages on `http://localhost:8080`.
3. Collector actuator/readiness is reachable from the RGM container (`wget`/`curl` to
   `http://collector:8081/actuator/health` or status via internal APIs).
4. A real Admin collect / search run still completes: RGM request → collector queue → GitHub → callback →
   product receipt → collector archive.

The full suite/95% coverage/CI-Codecov review and complete release/migration rehearsal remain #222.
