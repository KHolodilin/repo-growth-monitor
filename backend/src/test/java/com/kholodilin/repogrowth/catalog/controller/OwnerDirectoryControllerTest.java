package com.kholodilin.repogrowth.catalog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.service.OwnerDirectoryService;
import com.kholodilin.repogrowth.catalog.service.OwnerDirectoryService.OwnerRow;
import com.kholodilin.repogrowth.common.api.GlobalExceptionHandler;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class OwnerDirectoryControllerTest {

  ResourceAccessPolicy access;
  OwnerDirectoryService readModel;
  MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    access = mock(ResourceAccessPolicy.class);
    readModel = mock(OwnerDirectoryService.class);
    mockMvc =
        standaloneSetup(new OwnerDirectoryController(access, readModel))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void filtersHiddenOwnersBeforeCursorPaging() throws Exception {
    when(readModel.list(null))
        .thenReturn(
            List.of(
                new OwnerRow(1L, "acme", List.of(13L)),
                new OwnerRow(2L, "hidden", List.of(99L)),
                new OwnerRow(3L, "beta", List.of(14L))));
    when(access.filterReadable(eq(List.of(13L)), eq(PublicationService.Module.DASHBOARD)))
        .thenReturn(List.of(13L));
    when(access.filterReadable(eq(List.of(99L)), eq(PublicationService.Module.DASHBOARD)))
        .thenReturn(List.of());
    when(access.filterReadable(eq(List.of(14L)), eq(PublicationService.Module.DASHBOARD)))
        .thenReturn(List.of(14L));

    mockMvc
        .perform(get("/api/v1/owners").param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].login").value("acme"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.nextCursor").value("3"));

    mockMvc
        .perform(get("/api/v1/owners").param("cursor", "1").param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].login").value("beta"))
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  void exactLoginLookupAndInvalidCursor() throws Exception {
    when(readModel.list("acme")).thenReturn(List.of(new OwnerRow(1L, "acme", List.of(13L))));
    when(access.filterReadable(any(), eq(PublicationService.Module.DASHBOARD)))
        .thenReturn(List.of(13L));

    mockMvc
        .perform(get("/api/v1/owners").param("login", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].login").value("acme"));

    mockMvc
        .perform(get("/api/v1/owners").param("cursor", "bad"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mockMvc
        .perform(get("/api/v1/owners").param("cursor", "-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
