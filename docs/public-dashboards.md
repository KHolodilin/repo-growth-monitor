# Public owner and repository dashboards

Work package #220 adapts the existing dashboard and snapshot components. It retains historical data in place. The subsequent user-requested local rollout enables the combined Collector and a controlled 9-repository preview; see the combined-runtime follow-up below. Homepage and the sortable owners directory are #221; the complete release rehearsal belongs to #222.

## Routes and access

| Browser route | Content |
| --- | --- |
| `/` | English homepage: brand/Sign in chrome, Explore dashboards, four capability cards, sortable Owners table |
| `/{owner}` | Owner-scoped Traffic, Stats portfolio (public API only) |
| `/{owner}/{repository}` | Same `RepositoryDetailsPage` as admin (Overview/Traffic/Stats/Search/Topics/Events); anonymous GETs fenced to published repos |
| `/{owner}/{repository}/traffic/history` | Shared traffic history |
| `/{owner}/{repository}/search-queries/{queryId}` | Shared query details (published reads) |
| `/{owner}/{repository}/topics/{topic}` | Shared topic details (published reads) |
| `.../competitors/{githubId}` | Shared competitor cards |
| `/admin` → `/admin/repositories`, `/admin/search-runs/**` | Management screens behind Admin authorization |

Owner portfolio pages use only `/api/v1/public/**`. Published repository pages reuse the shared repository UI; anonymous reads go through `/api/v1/admin/repositories/{id}/**` only when publication allows them. Public event details contain no edit/delete controls. Admin sign-in lands on `/admin/repositories`; ordinary users land on `/`.

The server resolves publication before serving public HTML for the same SPA paths the client uses: `/{owner}`, `/{owner}/{repository}`, `.../traffic/history`, `.../search-queries/{id}`, `.../topics/{topic}`, and their competitor cards. Unknown, unpublished and wrong-parent nested routes return a real 404. Verified owner/repository aliases redirect with 308 to the current canonical path, preserving the nested SPA suffix and query string. Public documents and redirects use `Cache-Control: no-store`. A repository name containing a dot remains valid. The old broad SPA fallback and old management browser URLs are removed; no compatibility API is introduced.

## Shared rendering and data

`DashboardPresentation` reuses existing KPI, Traffic/Stats chart helpers, repository rows and highlights. Public adapters supply safe projections and canonical links. The repository view retains a Clones KPI. `SnapshotCards`, `SnapshotHistoryView`, `SnapshotHistoryChart` and `SnapshotHistoryTable` are shared, including mobile date selection and observed snapshot deltas.

Owner headings contain only the login; repository headings contain only the repository name. Breadcrumbs separate owner and repository. Anonymous/public repository pages keep a plain repository crumb — the admin `Switch repository` control stays admin-only so it cannot call `/api/v1/admin/repositories` and surface “Authentication required”. Owner-scoped rows and highlights omit the owner prefix. Popular Paths titles, history rows, chart legends, selectors, accessible names, CSV labels and PNG legends use relative paths. Full raw paths and stable IDs remain the selection/style identities. Prefix matching respects path-segment boundaries and known rename/transfer aliases. Cross-owner result/competitor names retain `owner/repository`.

The public repository-list API accepts `period` and projects stored stats and completed-day traffic; hidden/other-owner rows are removed before aggregation. Incomplete KPI totals keep `value=null` with `observedValue` for display; observed zero stays zero. Daily Traffic history matches Portfolio: a partial day keeps the sum of observed repositories and reports `missingRepositories` instead of blanking the series. Highlight computation reuses PortfolioHighlightMath after publication/query-proof filtering. Published definition metadata is available by stable ID, with the same parent gate as its history. The client verifies that the definition belongs to the displayed repository.

Owner repository lists page through bounded Public API cursors rather than truncating at 100. Daily charts follow date cursors. Oversized single-chart archives fail explicitly instead of silently dropping history. Source/rank archives expose incremental older-page controls. Requests are aborted on route/period changes, including pending pagination, so earlier responses cannot replace a different context.

## Preferences and chart identity

Public preferences use the bounded `rgm-public-preferences-v1` localStorage map: up to 256 entries, each generated value bounded to 16 KiB. Storage failures fall back to memory. Keys use stable owner/repository IDs plus chart/table/module context, independent of mutable names. Public preferences create no per-owner cookies and leave auth/CSRF cookie scope unchanged. Explicit URL periods win over saved preferences; repository switching preserves the selected URL period. Mobile metric, chart axes/events, legends, source selection and table sort remain scoped to their context.

All charts use the canonical seriesStyle registry. Path keys include repository identity; observed zeros have normal markers, missing values remain null, and Other remains neutral. Changing labels does not change series keys. Legend labels also format PNG exports; CSV keeps the visible relative labels. Tooltips remain bounded and dismiss on outside input/scroll/resize. Saved public data does not assert planner execution expectations, so it cannot fabricate a T-1 warning from an old or paused archive. Coverage and missing measurements remain visible independently.

Admin mobile metric state is held above loading skeletons so period reloads preserve the chosen metric.

## Targeted validation

- Backend: 54 passing tests across PublicApiIT, PublicationIT, PublicApiProtectionTest, PublicPageControllerTest, AuthSecurityIT and OidcLoginSuccessHandlerTest. Integration tests use disposable PostgreSQL 18.6, not the running product database. Cases cover period-scoped rows, hidden/module gates, direct definitions, HTML authorization, wrong-parent routes, canonical redirects and role-specific sign-in destinations.
- Frontend: the final shared-component batch passes 78 tests; the additional public/chart warning batch passes 32 tests (overlapping suites, not additive totals). Existing page-route and mobile reload regressions were also checked. Assertions cover stable string IDs, null/zero, relative CSV labels, raw path identities, bounded preferences, pagination and absence of private requests.
- Executable Maven reactor packaging and Vite production build pass. `git diff --check` passes. Whole-project `tsc --noEmit` continues to report the pre-existing chart/test utility errors recorded in #219; no new public-dashboard or modified snapshot-component errors remain. This is not a claim that the whole-project type check is green.
- Interactive browser QA uses the production frontend build against an isolated local Public API fixture, with no product database or GitHub token. Desktop and actual 320/390px checks cover owner/repository changes, canonical rename, two tabs, URL periods, mobile metric retention, Empty/Error/Retry/Stale, source date selection, legend hide/restore, bounded tooltip dismissal, full cross-owner competitor names, CSV and PNG downloads. The PNG was opened and checked for relative labels and matching series styles. The request log contains only Public API calls.

As agreed, complete suites, 95% coverage completion, final CI/Codecov inspection and full local Docker/migration rehearsal remain after #222. No merge, tag, release or production activation was performed.


## Local Docker follow-up

The first real Docker smoke check exposed that the generic two-segment entity mapping shadowed Spring's static resource handler: the document loaded but its `/assets/**` JS/CSS returned 404. Reserved first segments are now excluded in the mapping itself, before handler selection, while publication checks remain inside entity handlers. A real static test resource and a missing-asset regression cover successful serving and 404 behavior. PublicApiIT (21) and PublicPageControllerTest (4) pass after the fix.

At the user's request the local Compose application was rebuilt and restarted with its existing PostgreSQL volume. Liquibase applied the 10 pending changesets through 023 successfully. This local smoke check is separate from the complete #222 release/migration rehearsal; it does not enable publication or the new orchestration mode.


## User clarification: repository management only

There is no separate general Admin dashboard and no dashboard-to-user/repository assignment configuration. Dashboards live at owner/repository public URLs. The existing repository-management list remains the management entry point; Admin sign-in opens that list. Existing repository/query/topic/event management functionality is retained. Public publication policy is a separate unresolved local rollout step, not a new user-facing board configuration screen.

The initial local runtime audit exposed that only legacy workers were running. This was resolved in the collector runtime follow-up: contributor Compose runs separate RGM and Collector containers with independent Collector PostgreSQL, with actual end-to-end jobs verified.


## Combined runtime follow-up

The earlier runtime-audit gaps are resolved for the local contributor stack. RGM and Collector now execute in one JVM with a separate Collector database. Real Search and Discovery jobs completed, callbacks were applied and results archived. The local `/KHolodilin` preview contains its 9 selected verified PUBLIC repositories; publication used audited APIs after genuine metadata collection. See [combined runtime setup](collector-combined-local.md). No additional dashboard configuration screen or user/board mapping was introduced.
