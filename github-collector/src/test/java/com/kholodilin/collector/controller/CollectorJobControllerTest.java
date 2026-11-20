package com.kholodilin.collector.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.model.AttemptConflictException;
import com.kholodilin.collector.model.CapacityExceededException;
import com.kholodilin.collector.service.CollectorAcceptanceService;
import com.kholodilin.collector.service.CollectorJobMetrics;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class CollectorJobControllerTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID ATTEMPT = UUID.randomUUID();

  private CollectorAcceptanceService service;
  private CollectorJobMetrics metrics;
  private MockMvc mvc;
  private String body;

  @BeforeEach
  void setup() {
    service = mock(CollectorAcceptanceService.class);
    metrics = new CollectorJobMetrics();
    mvc = MockMvcBuilders.standaloneSetup(new CollectorJobController(service, metrics)).build();
    body =
        JSON.writeValueAsString(
            new JobRequest(
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                ATTEMPT,
                TaskType.STATS,
                new RepositoryTarget("202", "owner", "repo"),
                LocalDate.of(2030, 1, 1),
                1,
                Parameters.repository(),
                "rgm-results",
                Instant.parse("2030-01-01T12:00:00Z"),
                new AuthorizationScope("context", "github-main")));
  }

  private org.springframework.test.web.servlet.ResultActions submit(String key, String content)
      throws Exception {
    var request =
        post("/internal/v1/jobs")
            .principal(
                UsernamePasswordAuthenticationToken.authenticated("rgm", null, java.util.List.of()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(content);
    if (key != null) request.header("Idempotency-Key", key);
    return mvc.perform(request);
  }

  @Test
  void acceptedJobIsCreatedForTheAuthenticatedOriginAndCounted() throws Exception {
    var accepted =
        new JobAccepted(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            ATTEMPT,
            JobState.ACCEPTED,
            Instant.parse("2030-01-01T00:00:00Z"));
    when(service.accept(eq("rgm"), eq(ATTEMPT.toString()), any())).thenReturn(accepted);

    submit(ATTEMPT.toString(), body)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));

    assertThat(metrics.accepted()).isEqualTo(1);
    verify(service).accept(eq("rgm"), eq(ATTEMPT.toString()), any());
  }

  @Test
  void capacityIsServiceUnavailableWithRetryAfterAndCounted() throws Exception {
    when(service.accept(any(), any(), any()))
        .thenThrow(new CapacityExceededException("Queue is full"));

    submit(ATTEMPT.toString(), body)
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "30"))
        .andExpect(jsonPath("$.code").value("CAPACITY"))
        .andExpect(jsonPath("$.message").value("Queue is full"));

    assertThat(metrics.rejectedCapacity()).isEqualTo(1);
    assertThat(metrics.accepted()).isZero();
  }

  @Test
  void capacityWithoutMessageStillExplainsItself() throws Exception {
    when(service.accept(any(), any(), any())).thenThrow(new CapacityExceededException(null));

    submit(ATTEMPT.toString(), body)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.message").value("Collector capacity exceeded"));
  }

  @Test
  void attemptReusedWithDifferentContentIsAConflictNotABadRequest() throws Exception {
    when(service.accept(any(), any(), any())).thenThrow(new AttemptConflictException());

    submit(ATTEMPT.toString(), body)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
  }

  @Test
  void invalidEnvelopeIsABadRequest() throws Exception {
    when(service.accept(any(), any(), any()))
        .thenThrow(new IllegalArgumentException("Idempotency-Key must equal attemptId"));

    submit(ATTEMPT.toString(), body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void unreadableBodyAndMissingKeyAreBadRequests() throws Exception {
    submit(ATTEMPT.toString(), "{not json").andExpect(status().isBadRequest());
    submit(null, body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }
}
