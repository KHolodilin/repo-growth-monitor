package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.repogrowth.collection.repository.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.collection.service.CollectionRunMapper.CollectionRunResponse;
import com.kholodilin.repogrowth.common.api.ApiException;
import org.springframework.stereotype.Service;

/** Resource reads for collection runs. */
@Service
public class CollectionRunQueryService {

  private final CollectionRunJdbcRepository runs;
  private final CollectionRunMapper mapper;

  public CollectionRunQueryService(CollectionRunJdbcRepository runs, CollectionRunMapper mapper) {
    this.runs = runs;
    this.mapper = mapper;
  }

  public CollectionRunResponse get(long id) {
    return mapper.toResponse(
        mapper.toView(
            runs.findById(id)
                .orElseThrow(() -> ApiException.notFound("Collection run not found"))));
  }
}
