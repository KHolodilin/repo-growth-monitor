package com.kholodilin.collector.logging;

import org.slf4j.MDC;

/** Narrow MDC contract for collector logs: attempt/job/task correlation keys. */
public final class CollectorMdc {
  public static final String ATTEMPT = "attemptId";
  public static final String JOB = "jobId";
  public static final String TASK = "taskType";

  private CollectorMdc() {}

  public static void bindAttempt(String attemptId) {
    if (attemptId != null && !attemptId.isBlank()) MDC.put(ATTEMPT, attemptId);
  }

  public static void bindJob(String jobId, String taskType) {
    if (jobId != null) MDC.put(JOB, jobId);
    if (taskType != null) MDC.put(TASK, taskType);
  }

  public static void clear() {
    MDC.remove(ATTEMPT);
    MDC.remove(JOB);
    MDC.remove(TASK);
  }
}
