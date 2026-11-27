package com.kholodilin.repogrowth.collection.controller;

import com.kholodilin.repogrowth.collection.service.CollectionRunMapper.CollectionRunResponse;
import com.kholodilin.repogrowth.collection.service.CollectionRunQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Resource read for collection runs (ТЗ {@code GET /collection-runs/{id}}). */
@RestController
@RequestMapping("/api/v1/collection-runs")
public class CollectionRunResourceController {

  private final CollectionRunQueryService runs;

  public CollectionRunResourceController(CollectionRunQueryService runs) {
    this.runs = runs;
  }

  @GetMapping("/{id}")
  public CollectionRunResponse getRun(@PathVariable long id) {
    return runs.get(id);
  }
}
