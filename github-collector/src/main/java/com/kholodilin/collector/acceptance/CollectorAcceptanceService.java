package com.kholodilin.collector.acceptance;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.idempotency.ScopedIdempotency;
import com.kholodilin.collector.queue.JobQueue;
import com.kholodilin.idempotency.*;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

public final class CollectorAcceptanceService {
    private final IdempotencyService idempotency;
    private final TransactionTemplate tx;
    private final JobQueue queue;
    private final Set<String> destinations;
    private final JsonMapper json=JsonMapper.builder().build();
    public CollectorAcceptanceService(IdempotencyService idempotency,PlatformTransactionManager tm,JobQueue queue,Set<String> destinations) {
        this.idempotency=idempotency;this.tx=new TransactionTemplate(tm);this.queue=queue;this.destinations=Set.copyOf(destinations);
    }
    /** Origin is resolved from authenticated service identity, never a request field. */
    public JobAccepted accept(String trustedOrigin,String key,JobRequest request) {
        String scoped=ScopedIdempotency.key(trustedOrigin,key);
        if(!destinations.contains(request.destinationRef())) throw new IllegalArgumentException("Unknown result destination");
        return tx.execute(status -> idempotency.operation("ACCEPT_COLLECTOR_JOB").key(scoped).request(request)
            .execute(JobAccepted.class,()-> {
                if(!request.attemptId().toString().equals(key)) throw new IllegalArgumentException("Idempotency-Key must equal attemptId");
                var accepted=queue.accept(new JobQueue.Submission(trustedOrigin,request.jobId(),request.attemptId(),json.writeValueAsString(request),request.destinationRef(),request.deadlineAt()));
                return ExecutionResult.success(new JobAccepted(1,accepted.publicId(),request.jobId(),request.attemptId(),JobState.ACCEPTED,accepted.acceptedAt()));
            })).valueOrThrow();
    }
}
