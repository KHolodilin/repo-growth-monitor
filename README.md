# Repo Growth Monitor

[![Release](https://img.shields.io/github/v/release/KHolodilin/repo-growth-monitor)](https://github.com/KHolodilin/repo-growth-monitor/releases/latest)
[![Release workflow](https://img.shields.io/github/actions/workflow/status/KHolodilin/repo-growth-monitor/release.yml?label=release)](https://github.com/KHolodilin/repo-growth-monitor/actions/workflows/release.yml)
[![GHCR](https://img.shields.io/badge/GHCR-ghcr.io%2Fkholodilin%2Frepo--growth--monitor-blue)](https://github.com/KHolodilin/repo-growth-monitor/pkgs/container/repo-growth-monitor)
[![Docker Compose](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)](https://github.com/KHolodilin/repo-growth-monitor/releases/latest)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org)
[![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)](https://react.dev)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.8-3178C6?logo=typescript&logoColor=white)](https://www.typescriptlang.org)

Self-hosted GitHub repository growth analytics: traffic, search rankings, and a foundation for competitor discovery.

## Quick start

A git clone, Java, and Node are not required. The first start creates an empty database.

### 1. Download a compose file and start

PowerShell:

```powershell
Invoke-WebRequest -Uri https://github.com/KHolodilin/repo-growth-monitor/releases/latest/download/docker-compose.yml -OutFile docker-compose.yml
Invoke-WebRequest -Uri https://github.com/KHolodilin/repo-growth-monitor/releases/latest/download/env.example -OutFile env.example
Copy-Item env.example .env
# set GITHUB_TOKEN — see "Create a GitHub token" below
# set POSTGRES_PASSWORD
docker compose up -d
```

bash:

```bash
curl -fsSL -o docker-compose.yml https://github.com/KHolodilin/repo-growth-monitor/releases/latest/download/docker-compose.yml
curl -fsSL -o env.example https://github.com/KHolodilin/repo-growth-monitor/releases/latest/download/env.example
cp env.example .env
# set GITHUB_TOKEN — see "Create a GitHub token" below
# set POSTGRES_PASSWORD
docker compose up -d
```

From a clone, the same files live in `deploy/`:

```bash
cp deploy/.env.example .env
# set GITHUB_TOKEN and POSTGRES_PASSWORD
docker compose -f deploy/docker-compose.yml up -d
```

Open http://localhost:8080

1. Open **Repositories** and enable tracking.
2. Click **Collect now**, or wait for the planner window (`10:00–18:00` UTC by default).
3. View the **Dashboard** and repository details.

Stop with `docker compose down`. Data stays in the `pgdata` volume. Wipe everything with
`docker compose down -v`.

To upgrade, replace `docker-compose.yml` with the file from a newer Release and run
`docker compose up -d` again. Migrations apply on startup.

### 2. Create a GitHub token

Use a [Fine-grained personal access token](https://github.com/settings/personal-access-tokens/new).
A classic PAT is not required.

1. Open [GitHub → Settings → Developer settings → Fine-grained tokens](https://github.com/settings/personal-access-tokens).
2. Click **Generate new token**.
3. Set a name and an expiration.
4. **Resource owner**: your user, or the organization that owns the repositories.
   An organization must allow fine-grained tokens in its settings.
5. **Repository access**: **Only select repositories** and pick the ones you will track,
   or **All repositories** if you want the Repositories page to list everything the token can see.
6. Under **Repository permissions**, set:

   | Permission | Access | Why |
   |---|---|---|
   | **Metadata** | Read | Required by GitHub for every fine-grained token. Often selected automatically. |
   | **Contents** | Read | Repository files, README, and releases. |
   | **Administration** | Read | GitHub Traffic: views, clones, referrers, popular paths. Without this the rest of the app works, but traffic stays empty. |
   | **Issues** | Read | Growth Events from issues. |
   | **Pull requests** | Read | Growth Events from pull requests. |

   Leave every other permission at **No access**. Account permissions stay empty.
7. Generate the token and paste it into `.env` as `GITHUB_TOKEN`. The value starts with `github_pat_`.
   GitHub shows it once.

The token is never stored in PostgreSQL and never returned by the API. Do not commit `.env`.

## Local development

### Production-like stack (build from source)

Uses a Fine-grained PAT and a separate Postgres volume `pgdata_18`. This is not the
downloadable release: it builds the image on the machine.

```bash
cp .env.example .env
# set GITHUB_TOKEN — see "Create a GitHub token" above
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
| `GITHUB_TOKEN` | Fine-grained PAT. See [Create a GitHub token](#2-create-a-github-token). Never stored in PostgreSQL or returned by REST. |
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
