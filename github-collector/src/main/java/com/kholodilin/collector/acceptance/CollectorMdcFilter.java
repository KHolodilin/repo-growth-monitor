package com.kholodilin.collector.acceptance;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Puts Idempotency-Key into MDC as attemptId for accept logging. */
public final class CollectorMdcFilter extends OncePerRequestFilter {
  public static final String ATTEMPT = "attemptId", JOB = "jobId", TASK = "taskType";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String key = request.getHeader("Idempotency-Key");
    if (key != null && !key.isBlank()) MDC.put(ATTEMPT, key);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(ATTEMPT);
      MDC.remove(JOB);
      MDC.remove(TASK);
    }
  }

  public static void bindJob(String jobId, String taskType) {
    if (jobId != null) MDC.put(JOB, jobId);
    if (taskType != null) MDC.put(TASK, taskType);
  }
}
