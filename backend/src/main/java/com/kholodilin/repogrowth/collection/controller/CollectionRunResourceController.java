package com.kholodilin.repogrowth.collection.controller;

import com.kholodilin.repogrowth.collection.model.CollectionRun;
import com.kholodilin.repogrowth.collection.repository.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.collection.service.CollectionRunMapper;
import com.kholodilin.repogrowth.collection.service.CollectionRunMapper.CollectionRunResponse;
import com.kholodilin.repogrowth.common.api.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Resource read for collection runs (ТЗ {@code GET /collection-runs/{id}}). */
@RestController
@RequestMapping("/api/v1/collection-runs")
public class CollectionRunResourceController {

  private final CollectionRunJdbcRepository runRepository;
  private final CollectionRunMapper runMapper;

  public CollectionRunResourceController(
      CollectionRunJdbcRepository runRepository, CollectionRunMapper runMapper) {
    this.runRepository = runRepository;
    this.runMapper = runMapper;
  }

  @GetMapping("/{id}")
  public CollectionRunResponse getRun(@PathVariable long id) {
    CollectionRun run =
        runRepository
            .findById(id)
            .orElseThrow(() -> ApiException.notFound("Collection run not found"));
    return runMapper.toResponse(runMapper.toView(run));
  }
}
