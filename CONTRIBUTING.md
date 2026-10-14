# Contributing

Thanks for helping with Repo Growth Monitor.

## Reporting bugs and ideas

- Bugs and feature requests go through [GitHub issues](https://github.com/KHolodilin/repo-growth-monitor/issues).
- Vulnerabilities go through a [private security advisory](https://github.com/KHolodilin/repo-growth-monitor/security/advisories/new). See [SECURITY.md](SECURITY.md). Do not open a public issue for a security report.
- Never paste a GitHub token, `.env`, or other secret into an issue, pull request, or log.

## Local setup

The app needs Java 25 and Node 26 for a checkout, or Docker for a full stack. Day-to-day commands are in the README under [Local development](README.md#local-development).

Backend tests:

```bash
mvn -B -f backend/pom.xml test
```

Frontend tests, from `frontend/`:

```bash
npm ci
npm test
```

`npm test` runs Vitest with coverage. The frontend thresholds are 90% lines, statements, and functions, and 80% branches.

## Pull requests

1. Branch from `main`.
2. Keep the change focused. A bug fix and an unrelated docs change belong in separate pull requests.
3. Add or update tests for the behavior you change.
4. Open a pull request against `main`. CI runs the backend and frontend tests.

Do not commit `.env`, tokens, or generated coverage output.
