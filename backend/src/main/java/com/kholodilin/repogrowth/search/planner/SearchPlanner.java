package com.kholodilin.repogrowth.search.planner;

import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.search.repository.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.search.repository.SearchRunJdbcRepository;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchPlanner {

  public SearchPlanner(
      SearchQueryJdbcRepository queryRepository,
      SearchRunJdbcRepository runRepository,
      SearchProperties searchProperties) {
    Objects.requireNonNull(queryRepository);
    Objects.requireNonNull(runRepository);
    Objects.requireNonNull(searchProperties);
  }

  @Transactional
  public int planAll(LocalDate businessDate) {
    return planAll(businessDate, false);
  }

  @Transactional
  public int planAll(LocalDate businessDate, boolean snapshotDue) {
    throw unavailable();
  }

  @Transactional
  public int planRepository(long repositoryId, LocalDate businessDate) {
    throw unavailable();
  }

  @Transactional
  public long planQuery(long searchQueryId, long repositoryId, LocalDate businessDate) {
    throw unavailable();
  }

  private static ApiException unavailable() {
    return ApiException.serviceUnavailable("Collector orchestration is required");
  }
}
