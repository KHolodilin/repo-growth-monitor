package com.kholodilin.repogrowth.orchestration;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.*;

@Component
@ConditionalOnProperty(name="rgm.orchestration.enabled",havingValue="true")
public class CollectorDispatcher {
    private final DispatchRepository repository;
    private final CollectorTransport transport;
    private final String owner="dispatch-"+UUID.randomUUID();
    public CollectorDispatcher(DispatchRepository repository,CollectorTransport transport) {this.repository=repository;this.transport=transport;}
    @Scheduled(fixedDelayString="${rgm.orchestration.dispatch-interval:1000}")
    public void tick() {dispatchOne();}
    public boolean dispatchOne() {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Dispatch HTTP must run outside a transaction");
        var pending=repository.claim(owner); if(pending.isEmpty()) return false; var claim=pending.get();
        if(!claim.request().deadlineAt().isAfter(Instant.now())) {repository.expired(claim);return true;}
        try {repository.acknowledge(claim,transport.send(claim));}
        catch(RestClientResponseException error) {
            int code=error.getStatusCode().value(); boolean retry=code==408 || code==429 || code>=500;
            repository.failed(claim,retry?"HTTP_RETRY":"HTTP_REJECTED",retry);
        }
        catch(RestClientException error) {repository.failed(claim,"TRANSPORT",true);}
        catch(IllegalArgumentException error) {repository.failed(claim,"INVALID_ACCEPTANCE",false);}
        catch(IllegalStateException error) {repository.failed(claim,"UNTRUSTED_NODE",false);}
        return true;
    }
}
