package com.kholodilin.repogrowth.catalog.controller;

import com.kholodilin.repogrowth.administration.configuration.PublicRoutes;
import com.kholodilin.repogrowth.administration.service.*;
import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.service.PublicReadService;
import com.kholodilin.repogrowth.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/** Resolve publication before returning HTML. Unknown and unpublished paths are real 404s. */
@Controller
public class PublicPageController {
  // Exclude reserved first segments at handler selection, before a generic entity route can shadow
  // static resources.
  private static final String OWNER =
      "/{owner:(?!(?i:api|admin|login|logout|oauth2|settings|assets|actuator|dashboard|repositories|search-runs|error|v3|swagger-ui.*)$)[A-Za-z0-9][A-Za-z0-9-]{0,99}}";
  private static final String REPO = OWNER + "/{repository}";
  private final PublicReadService reads;
  private final Resource index;

  @org.springframework.beans.factory.annotation.Autowired
  public PublicPageController(PublicReadService reads) {
    this(reads, new ClassPathResource("static/index.html"));
  }

  PublicPageController(PublicReadService reads, Resource index) {
    this.reads = reads;
    this.index = index;
  }

  @GetMapping(
      value = {
        OWNER,
        REPO,
        REPO + "/traffic/history",
        REPO + "/search-queries/{queryId}",
        REPO + "/search-queries/{queryId}/competitors/{githubId}",
        REPO + "/topics/{topic}",
        REPO + "/topics/{topic}/competitors/{githubId}"
      },
      produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<Resource> page(
      @PathVariable String owner,
      @PathVariable(required = false) String repository,
      @PathVariable(required = false) Long queryId,
      @PathVariable(required = false) String topic,
      @PathVariable(required = false) Long githubId,
      @RequestParam(required = false) String kind,
      HttpServletRequest request) {
    if (!PublicRoutes.owner(owner) || repository != null && !PublicRoutes.repository(repository))
      return ResponseEntity.notFound().build();
    try {
      var target = reads.resolve("/" + owner + (repository == null ? "" : "/" + repository));
      String path = request.getRequestURI();
      String suffix =
          path.startsWith(target.canonicalPath())
              ? path.substring(target.canonicalPath().length())
              : "";
      if (repository != null) {
        if (path.endsWith("/traffic/history")) {
          boolean paths = "paths".equals(kind);
          reads.scope(
              owner,
              repository,
              paths ? PublicationService.Module.PATHS : PublicationService.Module.REFERRERS);
          suffix = "/traffic/history";
        } else if (queryId != null) {
          // HTML shell only needs a published parent definition. Competitor membership is enforced
          // by
          // the SPA/API the same way as in-app navigation; gating F5 on public_scope_verified made
          // reload 404 while click-through still worked for the same URL.
          reads.validateDefinition(owner, repository, false, queryId);
          suffix =
              "/search-queries/" + queryId + (githubId == null ? "" : "/competitors/" + githubId);
        } else if (topic != null) {
          reads.validateTopic(owner, repository, decode(topic));
          suffix = "/topics/" + topic + (githubId == null ? "" : "/competitors/" + githubId);
        }
      }
      if (target.redirect()) {
        String query = request.getQueryString();
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT)
            .location(
                URI.create(target.canonicalPath() + suffix + (query == null ? "" : "?" + query)))
            .cacheControl(CacheControl.noStore())
            .build();
      }
      return index.exists()
          ? ResponseEntity.ok()
              .cacheControl(CacheControl.noStore())
              .contentType(MediaType.TEXT_HTML)
              .body(index)
          : ResponseEntity.notFound().build();
    } catch (ApiException e) {
      return ResponseEntity.notFound().build();
    }
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
