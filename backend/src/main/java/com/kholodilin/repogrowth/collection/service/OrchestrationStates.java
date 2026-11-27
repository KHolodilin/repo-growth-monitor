package com.kholodilin.repogrowth.collection.service;

import java.util.Set;

/**
 * Product-DB state labels and one-off last_error codes used in SQL / JDBC maps. Not the wire {@link
 * com.kholodilin.collector.contracts.CollectorProtocol.JobState}.
 */
public final class OrchestrationStates {
  private OrchestrationStates() {}

  /** Task rows still expected to progress. */
  public static final Set<String> OPEN_TASK = Set.of("READY", "ACCEPTED", "RUNNING", "RETRY");

  /** Attempt rows that recovery may supersede. */
  public static final Set<String> ACTIVE_ATTEMPT = Set.of("ASSIGNED", "ACCEPTED", "RUNNING");

  /** Known terminal collector observations on an attempt. */
  public static final Set<String> TERMINAL_COLLECTOR =
      Set.of("SUCCEEDED", "FAILED", "RESULT_UNAVAILABLE");

  public static final String LAST_ERROR_ATTEMPT_LIMIT = "ATTEMPT_LIMIT";
  public static final String LAST_ERROR_SUPERSEDED = "SUPERSEDED";
  public static final String LAST_ERROR_DEFINITION_DELETED = "DEFINITION_DELETED";
  public static final String LAST_ERROR_DEADLINE = "DEADLINE_EXCEEDED";

  public static final String REASON_NOT_ACTIVE = "NOT_ACTIVE";
  public static final String REASON_NOT_CURRENT = "NOT_CURRENT";
  public static final String REASON_TERMINAL = "TERMINAL";
  public static final String REASON_TOO_RECENT = "TOO_RECENT";
  public static final String REASON_ATTEMPT_LIMIT = "ATTEMPT_LIMIT";
  public static final String REASON_REASSIGNED = "REASSIGNED";
}
