package com.kholodilin.repogrowth.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.collection.idempotency.InvalidIdempotencyKeyException;
import com.kholodilin.repogrowth.common.api.GitHubException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void mapsIdempotencyGitHubAndUnknownFailures() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/x");
    assertThat(handler.handleInvalidRequest(new InvalidIdempotencyKeyException()).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(handler.handleGitHub(GitHubException.auth(401, "no")).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(handler.handleGitHub(GitHubException.rateLimit(null, "slow")).getStatusCode())
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(handler.handleGitHub(GitHubException.notFound("x")).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(handler.handleGitHub(GitHubException.validation("x")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(handler.handleGitHub(GitHubException.api(502, true, null, "x")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(
            handler
                .handleMissingResource(
                    new NoResourceFoundException(HttpMethod.GET, "/missing", "/missing"), request)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(handler.handleDatabase(new DataAccessResourceFailureException("db")).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(handler.handleParameters(new IllegalArgumentException("bad")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(handler.handleUnknown(new RuntimeException("boom"), request).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
