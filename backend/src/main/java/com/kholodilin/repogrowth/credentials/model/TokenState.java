package com.kholodilin.repogrowth.credentials.model;

/** Product-visible GitHub credential readiness for the collector. */
public enum TokenState {
  MISSING,
  UNVERIFIED,
  ORCHESTRATION_DISABLED,
  COLLECTOR_UNAVAILABLE
}
