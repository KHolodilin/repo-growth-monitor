package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.repogrowth.collection.repository.CollectionRequestStatusJdbcRepository;
import com.kholodilin.repogrowth.common.api.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CollectionRequestStatusService {
  private final CollectionRequestStatusJdbcRepository status;

  public CollectionRequestStatusService(CollectionRequestStatusJdbcRepository status) {
    this.status = status;
  }

  public Map<String, Object> status(UUID id) {
    var rows = status.findRequest(id);
    if (rows.isEmpty()) throw ApiException.notFound("Collection request not found");
    var response = rows.getFirst();
    response.put("tasks", status.findTasks(id));
    return response;
  }
}
