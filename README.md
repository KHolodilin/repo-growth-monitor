# Repo Growth Monitor

Self-hosted GitHub repository growth analytics: traffic, search rankings, and a foundation for competitor discovery.

## Quick start

Two independent Compose files. Do not run both at once — they share host port `8080`.

### Mock stack (WireMock + test database)

No GitHub token. Isolated Postgres volume `pgdata_mock`, database `repogrowth_mock`.

```bash
docker compose -f docker-compose.mock.yml up -d --build
```

- App: http://localhost:8080
- WireMock: http://localhost:8081/__admin
- Test Postgres: `localhost:5433` (user/password `postgres`, db `repogrowth_mock`)

Planner window is always open. Stop with `docker compose -f docker-compose.mock.yml down`.

### Production-like stack (real GitHub)

Uses a Fine-grained PAT and a separate Postgres volume `pgdata`.

```bash
cp .env.example .env
# set GITHUB_TOKEN — Contents/Metadata Read; Traffic needs Administration: Read
docker compose up -d --build
```

Open http://localhost:8080

1. Open **Repositories** and enable tracking.
2. Click **Collect now**, or wait for the planner window (`10:00–18:00` UTC by default).
3. View the **Dashboard** and repository details.

Stop with `docker compose down`.

You do not need to install Java, Node.js, or PostgreSQL locally.

## Local development

Backend (Java 21, Maven):

```bash
cd backend
# PostgreSQL on localhost:5432, database repogrowth
GITHUB_TOKEN=... mvn spring-boot:run
```

Frontend:

```bash
cd frontend
npm install
npm run dev
```

Vite proxies `/api` to `http://localhost:8080`.

## Configuration

| Variable | Description |
|---|---|
| `GITHUB_TOKEN` | Fine-grained PAT. Never stored in PostgreSQL or returned by REST. |
| `SPRING_DATASOURCE_URL` | JDBC URL (set automatically in Compose). |
| `APP_TIMEZONE` | Timezone for `business_date` and the planner window. Default `UTC`. |
| `COLLECTION_PLANNER_FROM` / `TO` | Strict planning window. Default `10:00`–`18:00`. |
| `COLLECTION_WORKERS` | Parallel collection workers. Default `4`. |
| `SEARCH_WORKERS` | Search worker pool. Default `1`. |

The token is only accepted from environment / Docker secrets. Diagnostics expose a masked hint, never the raw value.

## API

- OpenAPI UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health
- Prometheus (optional scrape): http://localhost:8080/actuator/prometheus
