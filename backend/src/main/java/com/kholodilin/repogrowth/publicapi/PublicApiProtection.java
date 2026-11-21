package com.kholodilin.repogrowth.publicapi;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.kholodilin.repogrowth.common.web.AppHttpHeaders;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** Bound per-peer protection; untrusted forwarding headers cannot change the bucket key. */
public final class PublicApiProtection extends OncePerRequestFilter {
  private final Cache<String, Bucket> buckets =
      Caffeine.newBuilder().maximumSize(10000).expireAfterAccess(Duration.ofMinutes(10)).build();
  private final Set<String> trusted;
  private final long perMinute;
  private final long burst;
  private final JsonMapper json;

  public PublicApiProtection(long perMinute, long burst, JsonMapper json) {
    this(perMinute, burst, Set.of(), json);
  }

  public PublicApiProtection(long perMinute, long burst, Set<String> trusted, JsonMapper json) {
    this.trusted = Set.copyOf(trusted);
    if (perMinute < 1 || burst < 1) {
      throw new IllegalArgumentException("Positive public limits required");
    }
    this.perMinute = perMinute;
    this.burst = burst;
    this.json = Objects.requireNonNull(json, "json");
  }

  private String peer(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    if (!trusted.contains(remote)) {
      return remote;
    }
    String forwarded = request.getHeader(AppHttpHeaders.FORWARDED_FOR);
    if (forwarded == null || forwarded.length() > 2048) {
      return remote;
    }
    String[] hops = forwarded.split(",");
    if (hops.length > 16) {
      return remote;
    }
    for (int i = hops.length - 1; i >= 0; i--) {
      String candidate = hops[i].trim();
      if (!candidate.matches("[0-9a-fA-F:.]{2,64}")) {
        return remote;
      }
      if (!trusted.contains(candidate)) {
        return candidate;
      }
    }
    return remote;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
    var bucket =
        buckets.get(
            peer(request),
            key ->
                Bucket.builder()
                    .addLimit(
                        Bandwidth.builder()
                            .capacity(burst)
                            .refillGreedy(perMinute, Duration.ofMinutes(1))
                            .build())
                    .build());
    var probe = bucket.tryConsumeAndReturnRemaining(1);
    if (!probe.isConsumed()) {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setHeader(
          HttpHeaders.RETRY_AFTER,
          Long.toString(Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L)));
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      if (!request.getMethod().equals("HEAD")) {
        json.writeValue(
            response.getWriter(),
            json.createObjectNode()
                .put("code", "RATE_LIMITED")
                .put("message", "Too many requests")
                .put("requestId", UUID.randomUUID().toString()));
      }
      return;
    }
    if (request.getMethod().equals("HEAD")) {
      chain.doFilter(
          request,
          new HttpServletResponseWrapper(response) {
            @Override
            public ServletOutputStream getOutputStream() {
              return new ServletOutputStream() {
                public boolean isReady() {
                  return true;
                }

                public void setWriteListener(WriteListener listener) {}

                public void write(int value) {}
              };
            }

            @Override
            public PrintWriter getWriter() {
              return new PrintWriter(Writer.nullWriter());
            }
          });
    } else {
      chain.doFilter(request, response);
    }
  }
}
