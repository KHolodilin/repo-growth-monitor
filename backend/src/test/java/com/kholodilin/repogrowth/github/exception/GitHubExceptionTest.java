package com.kholodilin.repogrowth.github.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.kholodilin.repogrowth.common.api.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GitHubExceptionTest {
  @Test
  void factoriesAndContextPreserveMetadata() {
    Instant reset = Instant.parse("2026-11-22T00:00:00Z");
    GitHubException rate = GitHubException.rateLimit(reset, "slow");
    assertThat(rate.errorCode()).isEqualTo(ErrorCode.GITHUB_RATE_LIMIT_EXCEEDED);
    assertThat(rate.retryable()).isTrue();
    assertThat(rate.rateLimitReset()).isEqualTo(reset);
    assertThat(GitHubException.auth(401, "nope").statusCode()).isEqualTo(401);
    assertThat(GitHubException.notFound("missing").errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    assertThat(GitHubException.validation("bad").statusCode()).isEqualTo(422);
    assertThat(GitHubException.api(500, true, reset, "boom").retryable()).isTrue();
    assertThat(GitHubException.timeout(new RuntimeException("t")).getCause()).isNotNull();
    assertThat(GitHubException.malformed(new RuntimeException("m")).statusCode()).isEqualTo(200);
    assertThat(GitHubException.network(new RuntimeException("n")).retryable()).isTrue();
    assertThat(rate.withContext("repo").getMessage()).contains("repo").contains("slow");
  }
}
