# Local split RGM / Collector runtime

| Service | Image / role |
|---|---|
| `repo-growth-monitor` | Product UI/API, planner, orchestration (`collector.runtime.enabled=false`) |
| `collector` | Standalone `github-collector` boot JAR (in-memory queue; `Dockerfile.collector`) |

They talk over the Compose network with service tokens. Host port `8080` is RGM. Collector HTTP stays internal.
PostgreSQL holds **only** the product database (`repogrowth`). There is no collector database.

## Start

```sh
python deploy/prepare-combined-local.py
docker compose up -d --build
python deploy/bootstrap-combined-local.py
```

Provide collector secrets via `deploy/tmp/collector-runtime.local.yml` (see `deploy/collector-runtime.example.yml`).
`stop_grace_period: 30s` matches Spring `server.shutdown=graceful` / `spring.lifecycle.timeout-per-shutdown-phase`.

## Smoke

1. `docker compose ps` shows postgres, repo-growth-monitor, collector.
2. RGM on `http://localhost:8080`.
3. Collector readiness: `http://collector:8081/actuator/health` from the Compose network.
4. Admin collect: RGM dispatch → collector accept → GitHub → callback → RGM receipt.
