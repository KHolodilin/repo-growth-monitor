package com.kholodilin.repogrowth.orchestration;

import com.kholodilin.collector.idempotency.ScopedIdempotency;
import com.kholodilin.idempotency.*;
import com.kholodilin.idempotency.exception.IdempotencyConflictException;
import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.repogrowth.auth.application.UserAccountService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name="rgm.orchestration.enabled",havingValue="true")
public class IdempotentCollectionRequests {
    private final CollectionRequestService service;
    private final IdempotencyService idempotency;
    private final TransactionTemplate tx;
    private final UserAccountService users;
    private final JdbcTemplate jdbc;
    public IdempotentCollectionRequests(CollectionRequestService service,@Qualifier("rgmIdempotency") IdempotencyService idempotency,
            PlatformTransactionManager tm,UserAccountService users,JdbcTemplate jdbc) {
        this.service=service;this.idempotency=idempotency;this.tx=new TransactionTemplate(tm);this.users=users;this.jdbc=jdbc;
    }
    public CollectionRequestService.Created create(long actorId,String key,long repositoryId,LocalDate date) {
        return tx.execute(status -> {
            // Revalidate authority before even looking at a replay, including after account revocation.
            if(!users.canMutate(users.findById(actorId).orElse(null))) throw new AccessDeniedException("ADMIN role required");
            String scoped=ScopedIdempotency.key("user:"+actorId,key);
            return idempotency.operation("CREATE_COLLECTION_REQUEST").key(scoped).request(Map.of("repositoryId",repositoryId))
                .execute(CollectionRequestService.Created.class,()->{
                    var bindings=jdbc.queryForList("SELECT c.*,r.public_id FROM collection_request_command c JOIN collection_request r ON r.id=c.request_id WHERE c.command_key=?",scoped);
                    if(!bindings.isEmpty()) {
                        var binding=bindings.getFirst();
                        if(((Number)binding.get("repository_id")).longValue()!=repositoryId) throw new IdempotencyConflictException(new IdempotencyKey("CREATE_COLLECTION_REQUEST",scoped),"retained-target","different-target");
                        long request=((Number)binding.get("request_id")).longValue();
                        return ExecutionResult.success(new CollectionRequestService.Created((java.util.UUID)binding.get("public_id"),jdbc.queryForList("SELECT public_id FROM collection_task WHERE request_id=? ORDER BY id",java.util.UUID.class,request),(Boolean)binding.get("coalesced")));
                    }
                    var created=service.manual(repositoryId,date);
                    jdbc.update("INSERT INTO collection_request_command(command_key,actor_id,repository_id,request_id,coalesced) SELECT ?,?,?,id,? FROM collection_request WHERE public_id=?",scoped,actorId,repositoryId,created.coalesced(),created.requestId());
                    return ExecutionResult.success(created);
                }).valueOrThrow();
        });
    }
}
