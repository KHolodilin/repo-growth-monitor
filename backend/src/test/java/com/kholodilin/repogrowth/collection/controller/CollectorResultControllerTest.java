package com.kholodilin.repogrowth.collection.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.kholodilin.repogrowth.collection.service.CollectorResultIngestion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

class CollectorResultControllerTest {

  private CollectorResultIngestion ingestion;
  private MockMvc mvc;
  private Authentication auth;

  @BeforeEach
  void setUp() {
    ingestion = mock(CollectorResultIngestion.class);
    mvc =
        standaloneSetup(new CollectorResultController(ingestion, JsonMapper.builder().build()))
            .build();
    auth = UsernamePasswordAuthenticationToken.authenticated("node-a", null, List.of());
  }

  @Test
  void rejectsOversizedMalformedAndPersistenceFailures() throws Exception {
    mvc.perform(
            post("/internal/v1/collector-results")
                .principal(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(new byte[5 * 1024 * 1024 + 1]))
        .andExpect(status().isContentTooLarge());

    mvc.perform(
            post("/internal/v1/collector-results")
                .principal(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isBadRequest());

    doThrow(new IllegalArgumentException("bad")).when(ingestion).apply(anyString(), any());
    mvc.perform(
            post("/internal/v1/collector-results")
                .principal(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalBody()))
        .andExpect(status().isBadRequest());

    doThrow(new DuplicateKeyException("dup")).when(ingestion).apply(anyString(), any());
    mvc.perform(
            post("/internal/v1/collector-results")
                .principal(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalBody()))
        .andExpect(status().isConflict());

    doThrow(new DataAccessResourceFailureException("db")).when(ingestion).apply(anyString(), any());
    mvc.perform(
            post("/internal/v1/collector-results")
                .principal(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalBody()))
        .andExpect(status().isServiceUnavailable());
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
