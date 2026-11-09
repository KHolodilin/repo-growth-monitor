# Shared GitHub budget — implementation checkpoint

This checkpoint implements part of #214. It does not complete #214 or #215–#217.

Migration 017 adds central PostgreSQL budget state, exclusive per-identity request permits and response receipts. RGM authenticates Collector nodes on /internal/v1/github-budget/acquire and /report using their separate resolve service credentials and validates the assigned job before granting quota. Persistent state survives Collector/service restarts. Node identity and token version do not reset this state.

Defaults under rgm.github-budget: core-hourly 3000, search-per-minute 20, core-reserve 10, search-reserve 1, permit-lease 30s. Request spacing is computed from the configured ceiling/window. One active permit per credential reference coordinates both resources. Reports lower remaining counts and extend reset/cooldown conservatively. Long retry waits are returned as retryAt rather than held in a transaction. Acquisition/report take the same advisory-lock order.

Collector GitHubRequestLimiter calls that control plane, combines it with Bucket4j 8.14.0 local burst-one pacing and an independent local concurrency semaphore, and reports observed remaining/reset/Retry-After headers. Redirects are disabled. Missing control-plane grants fail closed. CredentialedGitHubRequest preserves retryAt on sanitized failures. The local pacer must be shared by all callers in a Collector instance.

Verified: CredentialCacheTest (13), LocalGitHubPacerTest (1), CredentialVaultIT (8), including durable permit/cooldown state across service reconstruction and duplicate permit acquisition. All 22 targeted tests pass; the reactor compiles. Full suites and coverage remain deferred as agreed.

Remaining before #214 can be closed:
- Integrate this limiter with every migrated GitHub transport/page/retry in #215; legacy execution is currently unchanged.
- Establish quota-principal aliases for multiple credential refs sharing a GitHub identity; the current authority keys by credential ref.
- Implement the unauthenticated IP profile, protected Admin diagnostics and bounded metrics.
- Expand fake-clock, cross-node concurrency, late-report/window reset and HTTP failure-injection checks. Expose/verify the policy for late reports and permit expiration.

#215 assigned-task execution, #216 result application/delivery, and #217 recovery/load validation are not implemented in this checkpoint. No production switch, historical-data relocation, merge or release is performed.
