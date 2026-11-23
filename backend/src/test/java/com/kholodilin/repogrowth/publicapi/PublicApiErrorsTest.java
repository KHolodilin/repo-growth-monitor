package com.kholodilin.repogrowth.publicapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.kholodilin.repogrowth.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

class PublicApiErrorsTest {
  private final PublicApiErrors errors = new PublicApiErrors();

  @Test
  void mapsPolicyDatabaseValidationAndUnknownFailures() {
    assertThat(errors.policy(ApiException.notFound("missing")).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(errors.database(new DataAccessResourceFailureException("db")).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(errors.parameters(new IllegalArgumentException("bad")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(errors.unknown(new RuntimeException("boom")).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
