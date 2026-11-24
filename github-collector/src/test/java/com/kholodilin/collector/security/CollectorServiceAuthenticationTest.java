package com.kholodilin.collector.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.kholodilin.collector.model.ServiceAuthSettings;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

class CollectorServiceAuthenticationTest {
  private static final String RGM_TOKEN = "rgm-token-with-at-least-32-characters!";
  private static final String OTHER_TOKEN = "other-token-with-at-least-32-characters";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final CollectorServiceAuthentication filter =
      new CollectorServiceAuthentication(
          new ServiceAuthSettings(
              java.util.List.of(
                  new ServiceAuthSettings.Client("rgm", RGM_TOKEN),
                  new ServiceAuthSettings.Client("other", OTHER_TOKEN))),
          JSON);

  private MockHttpServletResponse run(String authorization, AtomicReference<String> seen)
      throws Exception {
    var request = new MockHttpServletRequest("POST", "/internal/v1/jobs");
    if (authorization != null) request.addHeader("Authorization", authorization);
    request.addHeader("X-Origin", "attacker");
    var response = new MockHttpServletResponse();
    var chain =
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            seen.set(SecurityContextHolder.getContext().getAuthentication().getName());
          }
        };
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  void validBearerAuthenticatesTheMatchingOriginAndClearsTheContextAfterwards() throws Exception {
    var seen = new AtomicReference<String>();

    var response = run("Bearer " + OTHER_TOKEN, seen);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(seen).hasValue("other");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void eachTokenMapsToItsOwnOrigin() throws Exception {
    var seen = new AtomicReference<String>();

    run("Bearer " + RGM_TOKEN, seen);

    assertThat(seen).hasValue("rgm");
  }

  @Test
  void unknownMissingOrNonBearerCredentialsAreUnauthorizedBeforeTheChainRuns() throws Exception {
    for (String header : new String[] {null, "Bearer wrong", "Basic " + RGM_TOKEN, RGM_TOKEN}) {
      var seen = new AtomicReference<String>();

      var response = run(header, seen);

      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
      assertThat(response.getContentType()).contains("application/json");
      var body = JSON.readTree(response.getContentAsString());
      assertThat(body.path("code").asText()).isEqualTo("UNAUTHORIZED");
      assertThat(body.path("message").asText())
          .isEqualTo("Collector service authentication required");
      assertThat(seen.get()).isNull();
    }
  }
}
