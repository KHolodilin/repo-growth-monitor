package com.kholodilin.repogrowth.collection.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.collection.service.CollectorResultIngestion;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.json.JsonMapper;

class CollectorResultControllerTest {
  private final CollectorResultIngestion ingestion = mock(CollectorResultIngestion.class);
  private final JsonMapper json = JsonMapper.builder().build();
  private final CollectorResultController controller =
      new CollectorResultController(ingestion, json);

  @Test
  void rejectsOversizedMalformedAndPersistenceFailures() throws Exception {
    Authentication auth = mock(Authentication.class);
    when(auth.getName()).thenReturn("node-a");
    assertThat(controller.result(request(new byte[5 * 1024 * 1024 + 1]), auth).getStatusCode())
        .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    assertThat(
            controller.result(request("{".getBytes(StandardCharsets.UTF_8)), auth).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    doThrow(new IllegalArgumentException("bad")).when(ingestion).apply(anyString(), any());
    assertThat(controller.result(request(minimalBody()), auth).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    doThrow(new DuplicateKeyException("dup")).when(ingestion).apply(anyString(), any());
    assertThat(controller.result(request(minimalBody()), auth).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    doThrow(new DataAccessResourceFailureException("db")).when(ingestion).apply(anyString(), any());
    assertThat(controller.result(request(minimalBody()), auth).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  private static HttpServletRequest request(byte[] body) throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getInputStream())
        .thenReturn(
            new jakarta.servlet.ServletInputStream() {
              private final ByteArrayInputStream in = new ByteArrayInputStream(body);

              @Override
              public int read() {
                return in.read();
              }

              @Override
              public boolean isFinished() {
                return in.available() == 0;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(jakarta.servlet.ReadListener readListener) {}
            });
    return request;
  }

  private static byte[] minimalBody() {
    return """
    {
      "schemaVersion":1,
      "eventId":"11111111-1111-1111-1111-111111111111",
      "resultId":"66666666-6666-6666-6666-666666666666",
      "collectorJobId":"22222222-2222-2222-2222-222222222222",
      "jobId":"44444444-4444-4444-4444-444444444444",
      "attemptId":"55555555-5555-5555-5555-555555555555",
      "type":"STATS",
      "repositoryGithubId":"1",
      "status":"FAILED",
      "snapshot":null,
      "failure":{"code":"INVALID_REQUEST","message":"boom","retryable":false}
    }
    """
        .getBytes(StandardCharsets.UTF_8);
  }
}
