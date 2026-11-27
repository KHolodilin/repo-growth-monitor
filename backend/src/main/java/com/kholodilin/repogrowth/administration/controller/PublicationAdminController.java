package com.kholodilin.repogrowth.administration.controller;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.web.AppHttpHeaders;
import com.kholodilin.repogrowth.identity.security.AppUserPrincipal;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/admin")
public class PublicationAdminController {
  private final PublicationService policy;
  private final JsonMapper json;

  public PublicationAdminController(PublicationService policy, JsonMapper json) {
    this.policy = policy;
    this.json = json;
  }

  @GetMapping("/repositories/{id}/publication")
  public Object get(@PathVariable long id) {
    return policy.adminPublication(id);
  }

  public record Request(
      Boolean enabled, Boolean traffic, Boolean referrers, Boolean paths, Boolean topics) {}

  @PostMapping("/repositories/{id}/publication")
  @Transactional
  public Object publish(
      @PathVariable long id,
      @RequestBody Request request,
      Authentication auth,
      @RequestHeader(value = AppHttpHeaders.REQUEST_ID, required = false) String requestId) {
    if (request.enabled() == null) throw ApiException.validation("enabled is required");
    policy.lockRepository(id);
    var before = get(id);
    policy.publish(
        id,
        new PublicationService.Policy(
            request.enabled(),
            flag(request.traffic(), before, "public_traffic_enabled"),
            flag(request.referrers(), before, "public_referrers_enabled"),
            flag(request.paths(), before, "public_paths_enabled"),
            flag(request.topics(), before, "public_topics_enabled")));
    var after = get(id);
    audit(auth, "REPOSITORY", id, before, after, requestId);
    return after;
  }

  public record QueryPolicy(Boolean enabled) {}

  @PostMapping("/search-queries/{id}/publication")
  @Transactional
  public Object query(
      @PathVariable long id,
      @RequestBody QueryPolicy request,
      Authentication auth,
      @RequestHeader(value = AppHttpHeaders.REQUEST_ID, required = false) String requestId) {
    if (request.enabled() == null) throw ApiException.validation("enabled is required");
    var rows = policy.lockSearchQuery(id);
    if (rows.isEmpty()) throw ApiException.notFound("Search query not found");
    var before = rows.getFirst();
    if (request.enabled() && !policy.eligible(((Number) before.get("repository_id")).longValue()))
      throw ApiException.validation("Published parent required");
    policy.setSearchQueryPublicEnabled(id, request.enabled());
    var after = Map.of("public_enabled", request.enabled());
    audit(auth, "QUERY", id, before, after, requestId);
    return after;
  }

  private boolean flag(Boolean input, Object before, String name) {
    return input == null ? Boolean.TRUE.equals(((Map<?, ?>) before).get(name)) : input;
  }

  private void audit(
      Authentication auth, String type, long id, Object before, Object after, String request) {
    if (request != null && request.length() > 128)
      throw ApiException.validation("Request ID too long");
    policy.audit(
        ((AppUserPrincipal) auth.getPrincipal()).userId(),
        type,
        id,
        json.writeValueAsString(before),
        json.writeValueAsString(after),
        request);
  }
}
