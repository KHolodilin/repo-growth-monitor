package com.kholodilin.collector.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.kholodilin.collector.logging.CollectorMdc;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CollectorMdcFilterTest {
  @Test
  void idempotencyKeyIsBoundAsAttemptIdOnlyDuringTheRequest() throws Exception {
    var request = new MockHttpServletRequest("POST", "/internal/v1/jobs");
    request.addHeader("Idempotency-Key", "attempt-1");
    var during = new AtomicReference<String>();
    var chain =
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            during.set(MDC.get(CollectorMdc.ATTEMPT));
            CollectorMdc.bindJob("job-1", "STATS");
          }
        };

    new CollectorMdcFilter().doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(during).hasValue("attempt-1");
    assertThat(MDC.get(CollectorMdc.ATTEMPT)).isNull();
    assertThat(MDC.get(CollectorMdc.JOB)).isNull();
    assertThat(MDC.get(CollectorMdc.TASK)).isNull();
  }

  @Test
  void missingKeyBindsNothingAndTheContextIsStillClearedWhenTheChainFails() {
    var request = new MockHttpServletRequest("POST", "/internal/v1/jobs");
    var during = new AtomicReference<String>("untouched");
    var chain =
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            during.set(MDC.get(CollectorMdc.ATTEMPT));
            CollectorMdc.bindJob("job-1", "STATS");
            throw new IllegalStateException("boom");
          }
        };

    try {
      new CollectorMdcFilter().doFilter(request, new MockHttpServletResponse(), chain);
    } catch (Exception expected) {
      assertThat(expected).isInstanceOf(IllegalStateException.class);
    }

    assertThat(during.get()).isNull();
    assertThat(MDC.get(CollectorMdc.JOB)).isNull();
  }
}
