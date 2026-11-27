# Quality-gate ТЗ completion — repository wave (RGM-20)

Date: 27.11.2026 (Europe/Moscow).  
Branch: `issue-209-public-dashboard-collector-refactoring`.  
ArchitectureTest: **not modified**.

## Done this wave

### Repository / model layers
| Module | Model | Repository | Notes |
|---|---|---|---|
| administration | `PublicationModule`, `PublicationPolicy`, `PublicPathResolution` | `PublicationJdbcRepository` | PublicationService SQL extracted |
| credentials | `CredentialMetadata`, `TokenState`, `GitHubAccessStatus` | `CredentialJdbcRepository` | CredentialVault / GitHubAccessStatusService |
| stats | `RepositoryDailyStats`, `RepositoryStatDay` | `StatsJdbcRepository` | StatsReadService + AdminStatsHistoryService |
| catalog | — | `PublicReadJdbcRepository` | PublicReadService has no JDBC |
| collection | — | status/command/progress/budget/recovery/history/ingestion/node repos | Services own use-cases only |

**Leftover JDBC in services: empty** (verified: no `JdbcTemplate` / `NamedParameterJdbcTemplate` under `*/service/**`).

### Resource API (no `/admin` GET for product reads)
| Surface | Path |
|---|---|
| Events | `EventsResourceController` on `/api/v1/...`; `GrowthEventController` **deleted** |
| Dashboard / portfolio | `/api/v1/dashboard`, `/api/v1/portfolio` |
| Traffic / stats composites | `/api/v1/repositories/{id}/traffic`, `.../traffic/snapshot-history`, `.../stats-history` |
| Search / topics helpers | `/api/v1/repositories/{id}/search-visibility`, `topics-visibility`, by-topic charts |
| Collection request status | `/api/v1/collection-requests/{id}` |
| Collection runs | `/api/v1/collection-runs/{id}` |
| Search/topics CRUD + run | resource controllers / `CollectionResourceController` |

**Ops `/admin` kept (ТЗ):** system status, collectors, github-budget, credentials, publication mutations, repository entity CRUD (Catalog card ≠ admin `Repository` shape), collect/plan, refresh.

FE clients updated for events, search/topics runs, collection-requests, dashboard, traffic/stats composites. Repository entity GET remains `/api/v1/admin/repositories/{id}` deliberately.

### Collect planners → `collection.service`
| Bean | Package |
|---|---|
| `TopicPlanner`, `SearchPlanner`, `CollectionPlanner` | `collection.service` (`@Service`) |
| `DailyPlanner` | `collection.service` (`@Component`, scheduled use-case) |
| `PlanningWindow`, `SnapshotSchedule` | `collection.planner` (pure helpers only) |

`topics.planner` / `search.planner` packages **removed**. Topics/search keep watch/query domain repos/services only.

### TrackingRequest (ТЗ §4)
`administration.controller.TrackingRequest` — validation DTO on controller (`@NotNull`). No domain `TrackingCommand` introduced.

### GitHub wire DTOs
Live Jackson records remain under `collection.integration.github` (used by collector snapshot ingestion / event detection).  
**Deleted as unused:** Traffic*/Referrer/Path/Sidebar/SocialPreview/SearchResponse/ReleaseResponse/…  
**Leftover dead wire DTOs: empty.**

## Verification
- Backend compile: green.
- Unit tests (`mvn -B -f backend/pom.xml test -Dtest='!*IT'`): **290 run, 0 failures**.
- JaCoCo project check (≥95%): **not met on unit-only run** (~39% without `*IT` / Testcontainers). Same Docker-IT gap as prior waves — run full `mvn test` + ITs on a Docker host for the gate.
- ArchitectureTest untouched.

## Leftover
**empty** for this wave’s acceptance (repo layers for admin/creds/stats + JDBC-out-of-service, resource reads off `/admin`, planners in collection, TrackingRequest placement, dead github DTOs).

Still ops-by-design under `/api/v1/admin`: publication, system/collectors/budget/credentials, repository entity list/get/tracking, collect/plan/refresh.

**Out of acceptance / host limit:** full JaCoCo 95% + `*IT` (needs Docker).
