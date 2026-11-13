# Opt-in publication and canonical identity (#218)

Migration 022 adds five publication booleans and search_query.public_enabled, all OFF, publication_revision,
and visibility_verified_at. Existing GitHub IDs, tracking and snapshots are retained. Relationships from
owner/path aliases are logical, with no new physical foreign keys. A collision preflight stops migration
before case-insensitive unique owner-login and owner/name indexes; no automatic row deletion or ID merging.
A partial PUBLIC + dashboard-enabled owner/id index supports owner eligibility.

PublicationService checks the database on every resolution: explicitly enabled dashboard, PUBLIC metadata,
verified within rgm.publication.verification-max-age (default 24h), and a nonreserved owner namespace.
Tracking is independent. Private/internal/deleted verification blocks eligibility immediately; timeout/auth
failure does not invent visibility evidence. Verification requires matching GitHub ID and rejects older
observations/future timestamps. Successful Collector stats/discovery apply visibility and identity in the
same result transaction. Module/query access always includes the parent policy. Revisions change with
policy/identity/visibility verification, not tracking toggles. Publishing does not initiate GitHub requests.

Database triggers retain direct stable-ID aliases for repository rename/transfer and owner rename across
all existing persistence paths. Owner rename updates full_name consistently. Historical alias collisions
abort with an explicit reconciliation error instead of silently retargeting another identity. Stable IDs
and history remain unchanged. Current canonical paths win over exact repository aliases, then owner aliases
plus current/saved repository names. A current unpublished entity shadows an older published alias. Ambiguous
historical fallback fails closed. Alias resolution never follows chains.

PublicRoutes is the backend reserved registry; frontend/test-fixtures/reserved-public-segments.json is its
verified frontend contract for the later route implementation. Unknown/private/unpublished/expired resources
all raise the identical Page not found 404. Resolver returns the original-cased canonical path and a redirect
indicator for aliases/case/trailing slash; HTTP 308 and query preservation are API/page adapters in #219/#220.
Only one/two safe ASCII segments resolve; encoded separators, percent escapes, traversal, whitespace and
extra future badge/wrapped segments do not. A repository literally named badge remains valid.

No public HTTP handlers or Admin mutation endpoints are exposed by this issue; #219 will consume this policy
and resolver. Default OFF means no automatic publication of token-accessible repositories, avatars or GitHub
links. The shared Draft PR and final release-wide testing/95% coverage/Codecov/Docker gate remain unchanged.
