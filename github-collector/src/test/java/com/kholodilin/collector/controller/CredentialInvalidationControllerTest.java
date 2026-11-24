package com.kholodilin.collector.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kholodilin.collector.service.CredentialInvalidationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CredentialInvalidationControllerTest {
  private CredentialInvalidationService service;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    service = mock(CredentialInvalidationService.class);
    mvc = MockMvcBuilders.standaloneSetup(new CredentialInvalidationController(service)).build();
  }

  private org.springframework.test.web.servlet.ResultActions invalidate(String content)
      throws Exception {
    return mvc.perform(
        post("/internal/v1/credentials/invalidate")
            .principal(UsernamePasswordAuthenticationToken.authenticated("rgm", null, List.of()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(content));
  }

  @Test
  void originComesFromTheAuthenticationNotTheBody() throws Exception {
    invalidate("{\"context\":\"context-1\",\"origin\":\"attacker\"}")
        .andExpect(status().isNoContent());

    verify(service).invalidate("rgm", "context-1");
  }

  @Test
  void blankMissingOrOversizedContextIsRejectedWithoutInvalidating() throws Exception {
    invalidate("{\"context\":\" \"}").andExpect(status().isBadRequest());
    invalidate("{}").andExpect(status().isBadRequest());
    invalidate("{\"context\":\"" + "x".repeat(129) + "\"}").andExpect(status().isBadRequest());

    verify(service, never())
        .invalidate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void contextOfMaximumLengthIsAccepted() throws Exception {
    invalidate("{\"context\":\"" + "x".repeat(128) + "\"}").andExpect(status().isNoContent());
  }
}
