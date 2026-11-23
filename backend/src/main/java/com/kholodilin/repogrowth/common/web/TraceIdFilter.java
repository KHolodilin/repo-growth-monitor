package com.kholodilin.repogrowth.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TraceIdFilter extends OncePerRequestFilter {

  public static final String TRACE_ID = "traceId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String traceId = sanitizeTraceId(request.getHeader(AppHttpHeaders.TRACE_ID));
    MDC.put(TRACE_ID, traceId);
    response.setHeader(AppHttpHeaders.TRACE_ID, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.clear();
    }
  }

  private static String sanitizeTraceId(String raw) {
    if (raw == null || raw.isBlank()) {
      return UUID.randomUUID().toString();
    }
    String cleaned = raw.replace("\r", "").replace("\n", "").trim();
    if (cleaned.isEmpty() || cleaned.length() > 128) {
      return UUID.randomUUID().toString();
    }
    return cleaned;
  }
}
