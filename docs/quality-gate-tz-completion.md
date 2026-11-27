# Quality-gate ТЗ completion — repository wave (RGM-20)

Date: 27.11.2026 (Europe/Moscow).  
Branch: `issue-209-public-dashboard-collector-refactoring`.  
ArchitectureTest: **not modified**.

## Done this wave

### Layered architecture (`java-microservice.mdc`)
| Rule | Status |
|---|---|
| Controller → Service only (no repository/client bypass) | Done — `OwnerDirectoryService` fronts catalog owner directory; no controller→repository imports remain |
| Service → Repository / Integration / Model | Done |
| Repository / Integration → Model only | Done — `DispatchClaim` / `DispatchError` in `collection.model`; `CollectorTransport` no longer depends on repository types |
| No SQL in services | Done — verified no `JdbcTemplate` / `NamedParameterJdbcTemplate` / `JdbcClient` under `*/service/**` |
| No security / HTTP / `@ConfigurationProperties` in services | Done — access checks in controllers; `HttpStatus`/`AccessDeniedException`/`CurrentUserAccess`/`ResourceAccessPolicy` stripped from services; `PublicationConfiguration` owns `@EnableConfigurationProperties` |
| Models framework-free | Done — no Spring/Jackson/HTTP/SQL types under `*/model/**` (Jackson config for growth-event state lives in the repository) |

### Repository / model layers
| Module | Model | Repository | Notes |
|---|---|---|---|
| administration | `PublicationModule`, `PublicationPolicy`, `PublicPathResolution` | `PublicationJdbcRepository` | PublicationService SQL extracted |
| credentials | `CredentialMetadata`, `TokenState`, `GitHubAccessStatus` | `CredentialJdbcRepository` | CredentialVault / GitHubAccessStatusService |
| stats | `RepositoryDailyStats`, `RepositoryStatDay` | `StatsJdbcRepository` | StatsReadService + AdminStatsHistoryService |
| catalog | `RepositorySignals` | `PublicReadJdbcRepository`, `OwnerDirectoryReadModel` | PublicReadService / OwnerDirectoryService have no JDBC |
| collection | `DispatchClaim`, `DispatchError` | status/command/progress/budget/recovery/history/ingestion/node/dispatch repos | Services own use-cases; transport owns HTTP |
| events | `GrowthEventState` (plain record) | `GrowthEventStateJdbcRepository` | FAIL_ON_UNKNOWN_PROPERTIES configured in repository |

**Leftover JDBC in services: empty.**

### Resource API (no `/admin` GET for product reads)
| Surface | Path |
|---|---|
| Events | `EventsResourceController` on `/api/v1/...`; `GrowthEventController` **deleted** |
| Dashboard / portfolio | `/api/v1/dashboard`, `/api/v1/portfolio` |
| Traffic / stats composites | `/api/v1/repositories/{id}/traffic`, `.../traffic/snapshot-history`, `.../stats-history` |
| Search / topics helpers | `/api/v1/repositories/{id}/search-visibility`, `topics-visibility`, by-topic charts |
| Collection request status | `/api/v1/collection-requests/{id}` |
| Collection runs | `/api/v1/collection-runs/{id}` via `CollectionRunQueryService` |
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

### HTTP dispatch layering
`CollectorTransport` (integration) owns RestClient / HttpStatus and returns `SendResult`.  
`CollectorDispatcher` (service) claims + applies outcomes only — no HTTP types.

## Verification
- Backend compile: green.
- Unit tests (`mvn -B -f backend/pom.xml test -Dtest='!*IT'`): **294 run, 0 failures** (JaCoCo check fails without `*IT` / Docker — expected host limit).
- Unit confirmation with `-Djacoco.skip=true`: BUILD SUCCESS.
- ArchitectureTest untouched.

## Leftover
**empty** for this wave’s acceptance:

- modular layers (controller→service→repository/model)
- unified `/api/v1` resource reads (no product GET `/admin`)
- collection owns collect/run planners
- no SQL in services
- `java-microservice.mdc` compliance for this scope (no security/HTTP/`@ConfigurationProperties` in services; no framework types in models; no controller→repository; integration→model only)
- TrackingRequest placement
- dead GitHub wire DTOs removed
- branch ready for IT (unit green; ITs not run here)

Still ops-by-design under `/api/v1/admin`: publication, system/collectors/budget/credentials, repository entity list/get/tracking, collect/plan/refresh.

**Out of acceptance / host limit:** full JaCoCo 95% + `*IT` (needs Docker). Run full `mvn test` + ITs on a Docker host before merge quality gate.
