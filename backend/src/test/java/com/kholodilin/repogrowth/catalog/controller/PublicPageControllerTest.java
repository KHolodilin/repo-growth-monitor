package com.kholodilin.repogrowth.catalog.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.*;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.service.PublicHtmlService;
import com.kholodilin.repogrowth.common.api.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.web.servlet.MockMvc;

class PublicPageControllerTest {
  PublicHtmlService pages;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    pages = mock(PublicHtmlService.class);
    mvc =
        standaloneSetup(
                new PublicPageController(
                    pages, new ByteArrayResource("<!doctype html>".getBytes())))
            .build();
  }

  @Test
  void servesOnlyResolvedPagesAndKeepsDottedRepositoryNames() throws Exception {
    when(pages.resolve("/Alpha/project.js"))
        .thenReturn(new PublicationService.Resolved(1, 2L, "/Alpha/project.js", false));
    mvc.perform(get("/Alpha/project.js").accept("text/html"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().string("<!doctype html>"));
  }

  @Test
  void unknownOrReservedPathsAreNotSoft404s() throws Exception {
    when(pages.resolve("/Hidden")).thenThrow(ApiException.notFound("Page not found"));
    for (String path : new String[] {"/Hidden", "/api/unknown", "/admin/unknown", "/favicon.ico"})
      mvc.perform(get(path).accept("text/html")).andExpect(status().isNotFound());
    verify(pages, never()).resolve("/api/unknown");
  }

  @Test
  void aliasesRedirectPreserveNestedSpaSuffix() throws Exception {
    when(pages.resolve("/Old/project"))
        .thenReturn(new PublicationService.Resolved(1, 2L, "/Alpha/new-project", true));
    when(pages.scope("Old", "project", PublicationService.Module.PATHS))
        .thenReturn(java.util.List.of(2L));
    mvc.perform(get("/Old/project/traffic/history?kind=paths&period=7d").accept("text/html"))
        .andExpect(status().isPermanentRedirect())
        .andExpect(
            header().string("Location", "/Alpha/new-project/traffic/history?kind=paths&period=7d"));
    verify(pages).scope("Old", "project", PublicationService.Module.PATHS);
  }

  @Test
  void nestedSpaRoutesRequirePublishedParentAndDefinition() throws Exception {
    when(pages.resolve("/Alpha/project"))
        .thenReturn(new PublicationService.Resolved(1, 2L, "/Alpha/project", false));
    when(pages.scope("Alpha", "project", PublicationService.Module.REFERRERS))
        .thenReturn(java.util.List.of(2L));
    mvc.perform(get("/Alpha/project/traffic/history?kind=referrers").accept("text/html"))
        .andExpect(status().isOk());
    doThrow(ApiException.notFound("Page not found"))
        .when(pages)
        .validateDefinition("Alpha", "project", false, 99L);
    mvc.perform(get("/Alpha/project/search-queries/99").accept("text/html"))
        .andExpect(status().isNotFound());
    when(pages.validateTopic("Alpha", "project", "spring")).thenReturn(7L);
    mvc.perform(get("/Alpha/project/topics/spring").accept("text/html")).andExpect(status().isOk());
    mvc.perform(get("/Alpha/project/analytics/unsupported").accept("text/html"))
        .andExpect(status().isNotFound());
  }

  @Test
  void competitorHtmlServesShellWhenParentDefinitionIsPublished() throws Exception {
    when(pages.resolve("/Alpha/project"))
        .thenReturn(new PublicationService.Resolved(1, 2L, "/Alpha/project", false));
    doNothing().when(pages).validateDefinition("Alpha", "project", false, 5L);
    when(pages.validateTopic("Alpha", "project", "spring")).thenReturn(7L);
    mvc.perform(get("/Alpha/project/search-queries/5/competitors/555").accept("text/html"))
        .andExpect(status().isOk());
    mvc.perform(get("/Alpha/project/topics/spring/competitors/999").accept("text/html"))
        .andExpect(status().isOk());
    doThrow(ApiException.notFound("Page not found"))
        .when(pages)
        .validateDefinition("Alpha", "project", false, 99L);
    mvc.perform(get("/Alpha/project/search-queries/99/competitors/555").accept("text/html"))
        .andExpect(status().isNotFound());
  }
}
