package com.kholodilin.collector.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kholodilin.collector.contracts.CollectorProtocol.JobState;
import com.kholodilin.collector.contracts.CollectorProtocol.JobStatus;
import com.kholodilin.collector.service.RecoveryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class CollectorRecoveryControllerTest {
  private static final UUID ID = UUID.randomUUID();

  private RecoveryService recovery;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    recovery = mock(RecoveryService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new CollectorRecoveryController(recovery, JsonMapper.builder().build()))
            .build();
  }

  private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
    return request.principal(
        UsernamePasswordAuthenticationToken.authenticated("rgm", null, List.of()));
  }

  private static JobStatus jobStatus() {
    return new JobStatus(
        1,
        ID,
        UUID.randomUUID(),
        UUID.randomUUID(),
        JobState.RUNNING,
        Instant.parse("2030-01-01T00:00:00Z"),
        null,
        null);
  }

  @Test
  void nodeStatusIsScopedToTheAuthenticatedOrigin() throws Exception {
    when(recovery.nodeStatus("rgm")).thenReturn(Map.of("schemaVersion", 1));

    mvc.perform(as(get("/internal/v1/status")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schemaVersion").value(1));
  }

  @Test
  void jobAndAttemptStatusAreFoundOrNotFound() throws Exception {
    when(recovery.status(ID, "rgm")).thenReturn(Optional.of(jobStatus()));
    when(recovery.statusByAttempt(ID, "rgm")).thenReturn(Optional.empty());

    mvc.perform(as(get("/internal/v1/jobs/" + ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RUNNING"));
    mvc.perform(as(get("/internal/v1/jobs/by-attempt/" + ID))).andExpect(status().isNotFound());
  }

  @Test
  void foundAttemptStatusIsReturned() throws Exception {
    when(recovery.statusByAttempt(ID, "rgm")).thenReturn(Optional.of(jobStatus()));

    mvc.perform(as(get("/internal/v1/jobs/by-attempt/" + ID))).andExpect(status().isOk());
  }

  @Test
  void resultIsServedAsStoredJsonAndNeverCached() throws Exception {
    when(recovery.result(ID, "rgm")).thenReturn(Optional.of("{\"value\":42}"));
    when(recovery.result(ID, "other")).thenReturn(Optional.empty());

    mvc.perform(as(get("/internal/v1/jobs/" + ID + "/result")))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.value").value(42));
  }

  @Test
  void missingResultIsNotFound() throws Exception {
    when(recovery.result(ID, "rgm")).thenReturn(Optional.empty());

    mvc.perform(as(get("/internal/v1/jobs/" + ID + "/result"))).andExpect(status().isNotFound());
  }

  @Test
  void deliveryConfirmationMapsToNoContentOrNotFound() throws Exception {
    UUID event = UUID.randomUUID();
    when(recovery.confirmDelivery(ID, "rgm", event)).thenReturn(true);
    String content = "{\"eventId\":\"" + event + "\"}";
    String other = "{\"eventId\":\"" + UUID.randomUUID() + "\"}";

    mvc.perform(
            as(post("/internal/v1/jobs/" + ID + "/delivery/ack"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(content))
        .andExpect(status().isNoContent());
    mvc.perform(
            as(post("/internal/v1/jobs/" + ID + "/delivery/ack"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(other))
        .andExpect(status().isNotFound());
  }

  @Test
  void deliveryRetryMapsToNoContentOrNotFound() throws Exception {
    when(recovery.requeueDelivery(ID, "rgm")).thenReturn(true);

    mvc.perform(as(post("/internal/v1/jobs/" + ID + "/delivery/retry")))
        .andExpect(status().isNoContent());
    mvc.perform(
            post("/internal/v1/jobs/" + UUID.randomUUID() + "/delivery/retry")
                .principal(
                    UsernamePasswordAuthenticationToken.authenticated("rgm", null, List.of())))
        .andExpect(status().isNotFound());
  }
}
