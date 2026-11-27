package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.repogrowth.collection.integration.CollectorTransport;
import com.kholodilin.repogrowth.collection.repository.DispatchRepository;
import java.time.*;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.*;

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
    try {
      repository.acknowledge(claim, transport.send(claim));
    } catch (RestClientResponseException error) {
      // Preserve code >= 500 (includes codes above 599), not only the 5xx class.
      int code = error.getStatusCode().value();
      boolean retry =
          code == HttpStatus.REQUEST_TIMEOUT.value()
              || code == HttpStatus.TOO_MANY_REQUESTS.value()
              || code >= HttpStatus.INTERNAL_SERVER_ERROR.value();
      repository.failed(
          claim,
          retry ? DispatchError.HTTP_RETRY : DispatchError.HTTP_REJECTED,
          retry,
          retry ? retryAfter(error) : null);
    } catch (RestClientException error) {
      repository.failed(claim, DispatchError.TRANSPORT, true, null);
    } catch (IllegalArgumentException error) {
      repository.failed(claim, DispatchError.INVALID_ACCEPTANCE, false, null);
    } catch (IllegalStateException error) {
      repository.failed(claim, DispatchError.UNTRUSTED_NODE, false, null);
    }
    return true;
  }

  static Duration retryAfter(RestClientResponseException error) {
    String header =
        error.getResponseHeaders() == null
            ? null
            : error.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
    if (header == null || header.isBlank()) return null;
    try {
      long seconds = Long.parseLong(header.trim());
      if (seconds < 0) return null;
      return Duration.ofSeconds(Math.min(seconds, 3600));
    } catch (NumberFormatException ignored) {
      try {
        Duration until =
            Duration.between(Instant.now(), ZonedDateTime.parse(header.trim()).toInstant());
        return until.isNegative() ? Duration.ZERO : until;
      } catch (RuntimeException parse) {
        return null;
      }
    }
  }
}
