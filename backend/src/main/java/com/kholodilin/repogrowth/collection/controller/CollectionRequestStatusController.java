package com.kholodilin.repogrowth.collection.controller;

import com.kholodilin.repogrowth.collection.service.CollectionRequestStatusService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Resource read for collection-request status (ТЗ {@code GET /collection-requests/{requestId}}). */
@RestController
@RequestMapping("/api/v1/collection-requests")
public class CollectionRequestStatusController {
  private final CollectionRequestStatusService requests;

  public CollectionRequestStatusController(CollectionRequestStatusService requests) {
    this.requests = requests;
  }

  @GetMapping("/{id}")
  public Map<String, Object> status(@PathVariable UUID id) {
    return requests.status(id);
  }
}
