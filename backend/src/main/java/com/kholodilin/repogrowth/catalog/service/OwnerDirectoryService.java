package com.kholodilin.repogrowth.catalog.service;

import com.kholodilin.repogrowth.catalog.repository.OwnerDirectoryReadModel;
import java.util.List;
import org.springframework.stereotype.Service;

/** Catalog owner-directory reads for the resource API. */
@Service
public class OwnerDirectoryService {

  public record OwnerRow(long ownerId, String login, List<Long> repositoryIds) {}

  private final OwnerDirectoryReadModel readModel;

  public OwnerDirectoryService(OwnerDirectoryReadModel readModel) {
    this.readModel = readModel;
  }

  public List<OwnerRow> list(String exactLogin) {
    return readModel.list(exactLogin).stream()
        .map(row -> new OwnerRow(row.ownerId(), row.login(), row.repositoryIds()))
        .toList();
  }
}
