package com.kholodilin.collector.security;

import com.kholodilin.collector.logging.CollectorMdc;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Puts Idempotency-Key into MDC as attemptId for accept logging. */
public final class CollectorMdcFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    CollectorMdc.bindAttempt(request.getHeader("Idempotency-Key"));
    try {
      chain.doFilter(request, response);
    } finally {
      CollectorMdc.clear();
    }
  }
}
