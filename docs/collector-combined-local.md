# Local combined RGM / Collector runtime

The repository-root Compose stack runs **one Java process in one application container**. The same JVM
hosts RGM's planner/dispatcher/result ingestion and Collector's acceptance/execution/delivery components.
Their REST exchanges use authenticated `127.0.0.1:8080` endpoints. Collector owns a separate PostgreSQL
database in the same PostgreSQL instance. The `postgres` service hosts `repogrowth` for product data
and `collector` for the queue, results and history. Collector connects as the dedicated `collector` role.

The root `docker-compose.override.yml` enables this local topology automatically. The release pack in
`deploy/docker-compose.yml` is unchanged and is not a production activation.

## Starting a new local checkout

Configure the existing `.env` GitHub token, then run from the repository root:

```sh
python deploy/prepare-combined-local.py
docker compose up -d --build
# Wait for the application to start, then import the existing development token through Admin APIs.
python deploy/bootstrap-combined-local.py
docker compose restart repo-growth-monitor
```

Preparation starts PostgreSQL, provisions the Collector role and database, and generates
`deploy/tmp/combined-runtime.local.yml`, ignored by Git and the Docker build context. Encryption and independent service credentials are generated
once and preserved across reruns. Keep the encryption configuration with database backups. Bootstrap is
restricted to the local non-production MOCK stack and never prints the GitHub token. Existing vault
credentials are preserved; use `--rotate` explicitly when intentionally replacing the local token.

No additional repository/board configuration UI is introduced. Existing repository selection continues
to control collection. `rgm.credentials.default-ref` lets a newly selected, unassigned repository use the
configured default credential. First-use binding is serialized on the product repository row. An explicit
binding, including a revoked credential, is never replaced by the default. The token stays encrypted in
RGM, is resolved for an assigned task, and is cached by Collector for the configured 2h lease.

The planner is initially disabled while credentials are configured; bootstrap enables it on restart.
RGM owns all schedules and task creation. Collector only executes assigned jobs. Legacy product worker
pools/transport are disabled in orchestration mode. Existing rate-budget and idempotency policies apply.

## Verified local smoke

On 2026-11-09 the running stack was checked with one `java -jar /app/app.jar` process, independent product
and Collector Liquibase histories, and authenticated Collector status with `accepting=true` and
`executionEnabled=true`. A real GitHub Search command completed through RGM request → durable Collector
queue → GitHub → result callback → product receipt → acknowledged monthly Collector archive. A real
repository discovery request also completed through the same path. Completed jobs left the hot delivery
queue and appeared in `collector_job_history`.

The current local `KHolodilin` preview explicitly publishes its 9 selected, freshly verified PUBLIC
repositories using Admin publication APIs with audit records. Discovery supplied real visibility proof;
no timestamps or snapshots were fabricated. Private/untracked/other-owner repositories were not published.
This explicit local preview does not change global publication defaults or create a board-binding UI.

The full suite/95% coverage/CI-Codecov review and complete release/migration rehearsal remain #222.
