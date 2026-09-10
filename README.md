# Repo Growth Monitor

Self-hosted GitHub repository growth analytics: traffic, search rankings, and a foundation for competitor discovery.

## Quick start

Download `docker-compose.yml` and `env.example` from the
[latest Release](https://github.com/KHolodilin/repo-growth-monitor/releases/latest).
A git clone, Java, and Node are not required. The first start creates an empty database.

```bash
mkdir repo-growth-monitor && cd repo-growth-monitor
# save the two files from the Release into this folder
cp env.example .env
# set GITHUB_TOKEN — Contents/Metadata Read; Traffic needs Administration: Read
# set POSTGRES_PASSWORD
docker compose up -d
```

Open http://localhost:8080

1. Open **Repositories** and enable tracking.
2. Click **Collect now**, or wait for the planner window (`10:00–18:00` UTC by default).
3. View the **Dashboard** and repository details.

Stop with `docker compose down`. Data stays in the `pgdata` volume. Wipe everything with
`docker compose down -v`.

To upgrade, replace `docker-compose.yml` with the file from a newer Release and run
`docker compose up -d` again. Migrations apply on startup.

## Local development

### Production-like stack (build from source)

Uses a Fine-grained PAT and a separate Postgres volume `pgdata_18`. This is not the
downloadable release: it builds the image on the machine.

```bash
cp .env.example .env
# set GITHUB_TOKEN — Contents/Metadata Read; Traffic needs Administration: Read
docker compose up -d --build
```

Open http://localhost:8080. Stop with `docker compose down`.

### Mock stack (WireMock + test database)

No GitHub token. Isolated Postgres volume `pgdata_mock_18`, database `repogrowth_mock`.

```bash
docker compose -f docker-compose.mock.yml up -d --build
```

- App: http://localhost:8082
- WireMock: http://localhost:8081/__admin
- Test Postgres: `localhost:5433` (user/password `postgres`, db `repogrowth_mock`)

Planner window is always open. Stop with `docker compose -f docker-compose.mock.yml down`.

### Backend and frontend without Docker

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
| `POSTGRES_PASSWORD` | Required by the downloadable Compose file. Used by Postgres and the app. |
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
