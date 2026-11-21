package com.kholodilin.collector.security;

import com.kholodilin.collector.model.ServiceAuthSettings;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs before JSON decoding and every replay. Cookies and caller-provided origin headers are
 * ignored.
 */
@RequiredArgsConstructor
public final class CollectorServiceAuthentication extends OncePerRequestFilter {
  private final ServiceAuthSettings settings;
  private final JsonMapper json;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    String origin = null;
    byte[] supplied =
        (header != null && header.startsWith("Bearer ") ? header.substring(7) : "")
            .getBytes(StandardCharsets.UTF_8);
    for (var client : settings.clients())
      if (MessageDigest.isEqual(client.token().getBytes(StandardCharsets.UTF_8), supplied))
        origin = client.origin();
    if (origin == null) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      json.writeValue(
          response.getWriter(),
          json.createObjectNode()
              .put("code", "UNAUTHORIZED")
              .put("message", "Collector service authentication required"));
      return;
    }
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(origin, null, List.of()));
    SecurityContextHolder.setContext(context);
    try {
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
