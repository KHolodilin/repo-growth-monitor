# Shared GitHub request protection (#214)

Bucket4j 8.14.0 provides local bounded pacing; PostgreSQL-backed RGM control grants one shared in-flight
request per GitHub principal. Core, Search and GraphQL counters/cooldowns survive node/backend restarts,
token rotation and assignment changes. Every source call, including a single 401 credential-refresh retry,
requires a grant. Missing authority or unavailable RGM fails closed. No anonymous GitHub transport is
supported; a configured project credential is required, so no hidden unauthenticated/IP fallback exists.

```yaml
rgm:
  github-budget:
    core-hourly: 3000
    search-per-minute: 20
    core-reserve: 10
    search-reserve: 1
    permit-lease: 60s
```

Configured ceilings are conservative caps, not promises of GitHub entitlement. X-RateLimit remaining/reset
and Retry-After can only lower usable budget or extend waits. 403/429 protect all resources for that
principal; secondary-limit messages produce a protective delay. Response reporting is fenced by permit ID,
and lost reports retain a conservative wait until permit expiry. Local concurrency is one, local refill is
1200ms for Core/GraphQL and 3000ms for Search. HTTP timeout bounds must fit inside the shared lease.

Credential Admin rotation accepts optional `quotaIdentity`, for example `github:user:101`. All tokens for
the same GitHub user/app entitlement must use the same identity. Omitting it preserves the prior identity;
new unclassified credentials conservatively share `github:unclassified`, rather than each receiving fresh
quota. The identity is not a token or an authorization grant. Operators should assign verified identities
before initial activation and must not invent a new identity to bypass a cooldown. Migration conservatively
folds existing per-reference usage/cooldowns into the unclassified budget without replenishing it.

GET `/api/v1/admin/github-budget` is Admin-only and returns bounded operational counters, no secrets.
Micrometer `rgm.github.budget.decisions` has only resource/decision labels (no credential/repository labels).
Tests cover persistent cooldown, principal aliases, node reassignment, rotation, no quota reset after object
restart, 401 once, 403/429 non-refresh, real HTTP permits/reporting, no redirects and optional-file semantics.
GitHub protection reduces rate-limit risk; it cannot guarantee that GitHub never restricts an account.
