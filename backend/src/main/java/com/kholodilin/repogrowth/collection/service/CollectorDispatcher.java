package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.repogrowth.collection.integration.CollectorTransport;
import com.kholodilin.repogrowth.collection.integration.CollectorTransport.SendResult;
import com.kholodilin.repogrowth.collection.repository.DispatchRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Claims pending dispatches and applies transport outcomes — no RestClient / HttpStatus here. */
@Component
@ConditionalOnProperty(name = "rgm.orchestration.enabled", havingValue = "true")
public class CollectorDispatcher {
  private final DispatchRepository repository;
  private final CollectorTransport transport;
  private final String owner = "dispatch-" + UUID.randomUUID();

  public CollectorDispatcher(DispatchRepository repository, CollectorTransport transport) {
    this.repository = repository;
    this.transport = transport;
  }

  @Scheduled(fixedDelayString = "${rgm.orchestration.dispatch-interval:1000}")
  public void tick() {
    dispatchOne();
  }

  public boolean dispatchOne() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Dispatch HTTP must run outside a transaction");
    var pending = repository.claim(owner);
    if (pending.isEmpty()) return false;
    var claim = pending.get();
    if (!claim.request().deadlineAt().isAfter(Instant.now())) {
      repository.expired(claim);
      return true;
    }
    SendResult result = transport.send(claim);
    if (result instanceof SendResult.Accepted accepted) {
      repository.acknowledge(claim, accepted.accepted());
    } else if (result instanceof SendResult.Failed failed) {
      repository.failed(claim, failed.error(), failed.retry(), failed.retryAfter());
    }
    return true;
  }
}
