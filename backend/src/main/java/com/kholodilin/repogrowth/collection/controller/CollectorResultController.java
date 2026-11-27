package com.kholodilin.repogrowth.collection.controller;

import com.kholodilin.collector.contracts.CollectorProtocol.ResultDelivery;
import com.kholodilin.repogrowth.collection.service.CollectorResultIngestion;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ConditionalOnProperty(
    name = {"rgm.orchestration.enabled", "rgm.credentials.enabled"},
    havingValue = "true")
public class CollectorResultController {
  private final CollectorResultIngestion service;
  private final JsonMapper json;

  public CollectorResultController(CollectorResultIngestion service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  @PostMapping(
      value = "/internal/v1/collector-results",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> result(HttpServletRequest request, Authentication authentication)
      throws IOException {
    byte[] body = request.getInputStream().readNBytes(5 * 1024 * 1024 + 1);
    if (body.length > 5 * 1024 * 1024)
      return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
    ResultDelivery result;
    try {
      result = json.readValue(body, ResultDelivery.class);
    } catch (RuntimeException bad) {
      return ResponseEntity.badRequest().build();
    }
    try {
      service.apply(authentication.getName(), result);
      return ResponseEntity.noContent().build();
    } catch (tools.jackson.core.JacksonException | IllegalArgumentException malformed) {
      return ResponseEntity.badRequest().build();
    } catch (org.springframework.dao.DuplicateKeyException conflict) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    } catch (org.springframework.dao.DataAccessException unavailable) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
  }
}
