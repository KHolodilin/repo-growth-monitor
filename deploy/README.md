# Deploy pack (Caddy + loopback Postgres/Actuator)

VPS-oriented Compose stack for Repo Growth Monitor.

**Breaking change vs older quick start:** the app is no longer published as host `8080:8080`. Traffic goes through Caddy (`:80` / `:443`). Postgres is `127.0.0.1:5432`. Actuator is `127.0.0.1:8081` only (not proxied).

## Without a public domain (HTTP)

```bash
cd deploy
cp .env.example .env
# set GITHUB_TOKEN, POSTGRES_PASSWORD, POSTGRES_RUNTIME_PASSWORD

docker compose -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.build.yml up -d --build
```

Open **http://localhost/** (or `http://<vps-ip>/`).

Mock sign-in: `admin` / `admin` (after mock user sync).

Pull a released image instead of building:

```bash
# APP_IMAGE=ghcr.io/kholodilin/repo-growth-monitor:0.31.0 in .env
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d
```

## With a domain (HTTPS)

Set in `.env`:

```env
SITE_DOMAIN=monitor.example.com
APP_BASE_URL=https://monitor.example.com
AUTH_COOKIE_SECURE=true
CADDY_ACME_EMAIL=you@example.com
AUTH_PROVIDER=zitadel
AUTH_PRODUCTION=true
```

Point DNS A/AAAA to the VPS, open 80/443, then:

```bash
docker compose -f docker-compose.yml up -d
```

## Users CLI

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml run --rm --no-deps backend users list
docker compose … run --rm --no-deps backend users grant-admin --user-id <id>
docker compose … run --rm --no-deps backend users revoke-admin --user-id <id>
```

Uses the `cli` profile (no HTTP server / workers).

## SSH tunnels (loopback only)

```bash
ssh -L 5432:127.0.0.1:5432 -L 8081:127.0.0.1:8081 user@vps
```

- Postgres: `localhost:5432`
- Actuator: `http://localhost:8081/actuator/health`  
  Metrics/prometheus: set `MANAGEMENT_PASSWORD` and use HTTP basic (`MANAGEMENT_USER` / password).

## ZITADEL

1. Create a Web application (code + PKCE).
2. Redirect URI: `https://<SITE_DOMAIN>/login/oauth2/code/oidc` (or `http://<host>/…` for local HTTP).
3. Fill `AUTH_OIDC_*` in `.env`.
4. Grant `ADMIN` via `users grant-admin` after first login.

## Logs

- Backend JSON (profile `json-logs`): volume `app_logs` → `/var/log/app/app.log` (daily + 100 MiB, compress, 14d / 2 GiB).
- Caddy access: volume `caddy_logs`.
- Docker `json-file` rotation on each service.

## Runtime DB role

See [sql/runtime-grants.md](sql/runtime-grants.md). Migrate runs as Postgres; backend uses `repogrowth` without DDL.
