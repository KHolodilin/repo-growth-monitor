package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.topics.repository.TopicRunJdbcRepository;
import com.kholodilin.repogrowth.topics.repository.TopicWatchJdbcRepository;
import com.kholodilin.repogrowth.topics.service.TopicWatchSync;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TopicPlanner {

  public TopicPlanner(
      TopicWatchJdbcRepository watchRepository,
      TopicRunJdbcRepository runRepository,
      TopicWatchSync topicWatchSync,
      SearchProperties searchProperties) {
    Objects.requireNonNull(watchRepository);
    Objects.requireNonNull(runRepository);
    Objects.requireNonNull(topicWatchSync);
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
  public long planWatch(long topicWatchId, long repositoryId, LocalDate businessDate) {
    throw unavailable();
  }

  private static ApiException unavailable() {
    return ApiException.serviceUnavailable("Collector orchestration is required");
  }
}
