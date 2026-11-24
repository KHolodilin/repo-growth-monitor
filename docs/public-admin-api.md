# Public and Admin API separation (#219)

All former product API mappings moved to `/api/v1/admin/**`. `/api/v1/me` is unchanged. Old URLs
return 404 for an authenticated Admin; no compatibility controller or redirect is installed. Anonymous/USER
may be rejected by security before dispatch. Admin GET and mutations use the database-backed ADMIN check;
mutations retain session CSRF. Browser callers, including nested analytics and exports, use the new prefix.

## Public contracts

`/api/v1/public` has a separate stateless GET/HEAD security chain, ignores product session identity and
creates no session/CSRF cookie. The browser API helper omits cookies for public calls. PublicReadService
uses a read-only database transaction and explicit safe SQL projections; no planner, GitHub client,
TopicWatchSync, product authorization context or raw job/error DTO is involved.

| Route suffix | Response |
|---|---|
| `/resolve?path=/Owner/Repo` | canonical path and string owner/repository IDs |
| `/owners?sort=stars&order=desc&after=0&limit=50` | sortable published owners directory |
| `/owners/{owner}` / `/repositories/{owner}/{repository}` | safe metadata, canonicalPath, modules, freshness |
| `/owners/{owner}/repositories` | repository names/metadata, string IDs, bounded page |
| scope + `/stats`, `/stats-history` | scoped latest snapshots, comparison, contributor union/history |
| scope + `/traffic`, `/traffic-history` | explicitly enabled traffic, repository-day coverage |
| scope + `/referrers`, `/paths` | latest rolling snapshots, enabled separately |
| scope + `/referrer-history`, `/path-history` | bounded stored source history |
| scope + `/search-queries`, `/topics` | explicitly public query/existing topic definitions |
| scope + `/growth-events` | safe event projection, bounded page |
| `/search-queries/{id}/history`, `/topic-watches/{id}/history` | confirmed public rank snapshots |
| corresponding definition + `/results`, `/series`, `/competitors/{githubId}` | bounded safe rows or stored competitor metadata |
| `/search-runs/{id}/results`, `/topic-runs/{id}/results` | direct run read with full parent publication check |

Here scope is `/owners/{owner}` or `/repositories/{owner}/{repository}`. Unknown, unpublished, private,
expired or disabled modules return the same 404. Query/watch/run IDs never bypass parent checks, including
Admin callers using the Public URL. Restricted/service paths are excluded regardless of request parameters.
Canonical aliases use the #218 resolver. Public IDs and cursors are strings; counters aggregate as long.

Limits are 1..100. Forward row pages use `after`; descending run/event/source history uses `before`.
Date history uses exclusive ISO-date `before` and returns `nextDate`; ID pages return `nextCursor`.
Directory `after` is a bounded offset (0..10000), sorting is a whitelist with stable owner-ID tie breaks.
All-time requests remain paged. Missing observations are not manufactured zeros. Totals have value/null,
observedValue, completeness/coverage, absoluteChange and growthPercent; comparisons require complete
current and baseline observations. Contributors are a case-insensitive union of collected logins; incomplete
or anonymous coverage yields an unknown exact value. Partial history is explicit. Tracking OFF does not
remove an otherwise eligible published repository.

## Private-search protection

Authenticated GitHub Search can include private candidates. Migration 023 adds `public_scope_verified`
to search/topic runs, default FALSE: old history is retained for Admin but not assumed safe for Public.
RGM freezes `Parameters.publicOnly` when a query/topic module is explicitly published. Collector appends
`is:public`, checks result private flags and records the observation. RGM independently checks public-only
assignment and every raw item again before marking the run safe. Public history/results/series/cards only
read verified runs; no private counts/ranks from unverified windows are exposed. Cached competitor profiles
also require independently verified PUBLIC repository metadata, otherwise the stored rank remains readable
with metadataComplete=false. Publication followed by an already-active older assignment may therefore show
empty public history until a new confirmed public-only collection completes. No old snapshots are rewritten.

## Administrative operations

Repository list/detail/tracking, dashboard/portfolio, traffic/stats/history, query CRUD, topic operations,
growth events/settings and system/status retain their functions under `/admin`. Collect, Search Run/Run All
and Topic Run/Run All dispatch explicit immutable jobs when orchestration is enabled. Targeted commands
only create requested families; a subsequent full Collect appends missing tasks under the same request
lock. Frozen parameters are not changed. Schedule replay retains its completed request instead of planning
again. Deleting a queued query cancels assignments/dispatch first; late callbacks become IGNORED.

Admin dashboard operational progress includes new requests before their first callback, so waiting for
GitHub quota does not make Collect look complete. The original default execution flags stay unchanged
until the agreed final activation; legacy execution remains available only in the unswitched runtime mode.

- POST `/admin/repositories/{id}/collections`: starter-backed manual command with Idempotency-Key.
- POST `/admin/repositories/{id}/collect`: Collect button command using the same orchestration service.
- POST `/admin/repositories/refresh`: durable discovery, 201 plus committed request/task IDs.
- GET `/admin/collection-requests/{uuid}`: state and task progress; no job payload/secret.
- GET repositories with refresh=true is rejected. Initial list load does not initiate a refresh.
  Browser Refresh posts explicitly and follows request status until saved data updates; unmount aborts polling.
- POST `/admin/repositories/{id}/publication`: `{enabled, traffic?, referrers?, paths?, topics?}`.
  Omitted module flags are retained when the parent is disabled. Response includes policy/revision/canonicalPath.
- POST `/admin/search-queries/{id}/publication`: `{enabled}`; enabling requires an eligible published parent.

Publication mutations atomically validate verified public visibility/route policy, update persisted revision,
and insert publication_audit with actor/entity/before/after/request ID. Query publication bumps the parent
revision. No secret is audited. Private/unverified publication cannot be enabled. Public responses are not
shared-cacheable without revalidation (`Cache-Control: no-cache`); no response cache can delay revocation.

## Protection and verification

```yaml
rgm:
  public-api:
    requests-per-minute: 120
    burst: 30
    trusted-proxy-addresses: "" # exact controlled proxy peer IPs, comma-separated
```

Per-peer Bucket4j state is bounded (10000 entries). Forwarded headers are ignored unless the direct peer
is configured as trusted; then the chain is walked from the right to the first untrusted peer. Changing
period/path or forging the leftmost X-Forwarded-For does not create a new allowance. Configure the actual
Caddy peer addresses before relying on per-client limits behind the reverse proxy. 429 includes Retry-After;
HEAD produces no body. Errors are sanitized JSON with requestId; SQL failures return 503 without SQL text.

Targeted PostgreSQL/MockMvc tests cover mixed owners/private/unpublished data, contributor union/partial
coverage, zero/missing traffic, direct-ID gates, restricted paths, stateless role invariance, GET/HEAD with
no writes or GitHub calls, bounded pagination/sort, audit rollback/revoke, durable target expansion/cancel,
private-search proof and Admin role/CSRF. Controller/auth/search/topic regressions remain exercised.
Frontend tests cover migrated callers, explicit refresh and authentication; Vite builds successfully.
The complete suite/95% coverage/CI-Codecov review and full browser/Docker/migration release rehearsal remain
at #222 as agreed. No production switch, deployment, merge or release is performed for this issue.

Whole-project `tsc --noEmit` is not green: existing chart/test utility type errors remain for the final #222 gate. The changed HTTP/refresh behavior passes targeted Vitest tests and Vite build.
