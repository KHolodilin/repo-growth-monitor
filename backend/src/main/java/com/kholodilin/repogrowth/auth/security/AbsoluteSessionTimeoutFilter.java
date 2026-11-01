package com.kholodilin.repogrowth.auth.security;

import com.kholodilin.repogrowth.auth.config.AuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    public static final String CREATED_AT_ATTR = "AUTH_SESSION_CREATED_AT";

    private final Duration absoluteTimeout;
    private final Clock clock;

    public AbsoluteSessionTimeoutFilter(AuthProperties properties, Clock clock) {
        this.absoluteTimeout = properties.sessionAbsolute();
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object created = session.getAttribute(CREATED_AT_ATTR);
            Instant createdAt;
            if (created instanceof Instant instant) {
                createdAt = instant;
            } else if (created instanceof Long epochMilli) {
                createdAt = Instant.ofEpochMilli(epochMilli);
            } else {
                createdAt = Instant.now(clock);
                session.setAttribute(CREATED_AT_ATTR, createdAt);
            }
            if (Duration.between(createdAt, Instant.now(clock)).compareTo(absoluteTimeout) > 0) {
                session.invalidate();
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
