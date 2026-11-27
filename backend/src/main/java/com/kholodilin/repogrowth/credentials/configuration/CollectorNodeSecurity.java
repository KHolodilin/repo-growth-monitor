package com.kholodilin.repogrowth.credentials.configuration;

import com.kholodilin.repogrowth.collection.configuration.OrchestrationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rgm.credentials.enabled", havingValue = "true")
public class CollectorNodeSecurity {
  @Bean
  @Order(-10)
  SecurityFilterChain credentialResolutionChain(
      HttpSecurity http, OrchestrationProperties properties, JsonMapper json) throws Exception {
    return http.securityMatcher(
            "/internal/v1/credentials/resolve",
            "/internal/v1/github-budget/acquire",
            "/internal/v1/github-budget/report",
            "/internal/v1/collector-results")
        .csrf(c -> c.disable())
        .requestCache(c -> c.disable())
        .formLogin(c -> c.disable())
        .httpBasic(c -> c.disable())
        .logout(c -> c.disable())
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .securityContext(c -> c.securityContextRepository(new NullSecurityContextRepository()))
        .authorizeHttpRequests(c -> c.anyRequest().authenticated())
        .addFilterBefore(new NodeFilter(properties, json), AnonymousAuthenticationFilter.class)
        .build();
  }

  static final class NodeFilter extends OncePerRequestFilter {
    private final OrchestrationProperties properties;
    private final JsonMapper json;

    NodeFilter(OrchestrationProperties properties, JsonMapper json) {
      this.properties = properties;
      this.json = json;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String header = request.getHeader(HttpHeaders.AUTHORIZATION);
      String node = null;
      byte[] token =
          (header != null && header.startsWith("Bearer ") ? header.substring(7) : "")
              .getBytes(StandardCharsets.UTF_8);
      for (var candidate : properties.nodes()) {
        if (candidate.enabled()
            && candidate.resolveToken() != null
            && candidate.resolveToken().length() >= 32
            && MessageDigest.isEqual(
                candidate.resolveToken().getBytes(StandardCharsets.UTF_8), token)) {
          node = candidate.key();
        }
      }
      if (node == null) {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getWriter(), json.createObjectNode().put("code", "UNAUTHORIZED"));
        return;
      }
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(
          UsernamePasswordAuthenticationToken.authenticated(node, null, List.of()));
      SecurityContextHolder.setContext(context);
      try {
        chain.doFilter(request, response);
      } finally {
        SecurityContextHolder.clearContext();
      }
    }
  }
}
