package com.kholodilin.collector.integration.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import com.kholodilin.collector.integration.credentials.CredentialedGitHubRequest.Failure;
import com.kholodilin.collector.model.GitHubAuthenticationFailedException;
import com.kholodilin.collector.model.GitHubNotFoundException;
import com.kholodilin.collector.model.GitHubPermissionDeniedException;
import com.kholodilin.collector.model.GitHubRateLimitedException;
import com.kholodilin.collector.model.GitHubResponseTooLargeException;
import com.kholodilin.collector.model.GitHubSourceException;
import com.kholodilin.collector.model.GitHubSourceUnavailableException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** HTTP statuses are translated to semantic errors inside the integration layer. */
class SemanticFailureTest {
  @ParameterizedTest
  @CsvSource({
    "401,GitHubAuthenticationFailedException",
    "403,GitHubPermissionDeniedException",
    "404,GitHubNotFoundException",
    "413,GitHubResponseTooLargeException",
    "429,GitHubSourceUnavailableException",
    "500,GitHubSourceUnavailableException",
    "0,GitHubSourceUnavailableException"
  })
  void statusWithoutRetryAtMapsToSemanticError(int status, String type) {
    GitHubSourceException semantic = new Failure(status).toSemantic();

    assertThat(semantic.getClass().getSimpleName()).isEqualTo(type);
  }

  @ParameterizedTest
  @CsvSource({"403", "429"})
  void deferralWithRetryAtIsRateLimitedAndKeepsTheInstant(int status) {
    Instant retryAt = Instant.parse("2030-01-01T00:10:00Z");

    var semantic = new Failure(status, retryAt).toSemantic();

    assertThat(semantic).isInstanceOf(GitHubRateLimitedException.class);
    assertThat(((GitHubRateLimitedException) semantic).retryAt()).isEqualTo(retryAt);
  }

  @Test
  void retryAtOnOtherStatusesDoesNotMakeThemRateLimited() {
    var semantic = new Failure(404, Instant.now()).toSemantic();

    assertThat(semantic).isInstanceOf(GitHubNotFoundException.class);
    assertThat(new Failure(401, Instant.now()).toSemantic())
        .isInstanceOf(GitHubAuthenticationFailedException.class);
    assertThat(new Failure(413, Instant.now()).toSemantic())
        .isInstanceOf(GitHubResponseTooLargeException.class);
    assertThat(new Failure(502, Instant.now()).toSemantic())
        .isInstanceOf(GitHubSourceUnavailableException.class);
    assertThat(new Failure(403).toSemantic()).isInstanceOf(GitHubPermissionDeniedException.class);
  }
}
