package com.kholodilin.repogrowth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.kholodilin.repogrowth.identity.config.AuthProperties;
import com.kholodilin.repogrowth.identity.security.AbsoluteSessionTimeoutFilter;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AbsoluteSessionTimeoutFilterTest {

  @Test
  void invalidatesExpiredSession() throws Exception {
    Instant created = Instant.parse("2026-01-01T00:00:00Z");
    Clock clock = Clock.fixed(created.plus(Duration.ofHours(25)), ZoneOffset.UTC);
    AuthProperties properties =
        new AuthProperties("mock", false, Duration.ofHours(8), Duration.ofHours(24), null, null);
    AbsoluteSessionTimeoutFilter filter = new AbsoluteSessionTimeoutFilter(properties, clock);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.getSession(true).setAttribute(AbsoluteSessionTimeoutFilter.CREATED_AT_ATTR, created);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(request.getSession(false)).isNull();
    verify(chain).doFilter(request, response);
  }

  @Test
  void stampsCreationWhenMissing() throws Exception {
    Instant now = Instant.parse("2026-01-01T12:00:00Z");
    AbsoluteSessionTimeoutFilter filter =
        new AbsoluteSessionTimeoutFilter(
            new AuthProperties("mock", false, null, Duration.ofHours(24), null, null),
            Clock.fixed(now, ZoneOffset.UTC));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.getSession(true);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(request.getSession(false).getAttribute(AbsoluteSessionTimeoutFilter.CREATED_AT_ATTR))
        .isEqualTo(now);
  }
}
