package com.kholodilin.repogrowth.common.api;

/**
 * Wording / surface classification for HTTP APIs. Resource reads move to unified {@code /api/v1}
 * paths; ops diagnostics stay under {@code /api/v1/admin/*}; collector protocol stays {@code
 * /internal/v1/*}.
 */
public enum ApiSurface {
  /** Unified anonymous/user/admin resource reads and resource-scoped commands. */
  RESOURCE,
  /** Admin-only operational diagnostics and management commands. */
  OPS,
  /** Collector/protocol internal endpoints (not browser resource API). */
  INTERNAL
}
