package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.idempotency.*;
import com.kholodilin.idempotency.exception.IdempotencyConflictException;
import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.repogrowth.collection.idempotency.ScopedIdempotency;
import com.kholodilin.repogrowth.collection.repository.CollectionRequestCommandJdbcRepository;
import com.kholodilin.repogrowth.identity.service.UserAccountService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "rgm.orchestration.enabled", havingValue = "true")
public class IdempotentCollectionRequests {
  private final CollectionRequestService service;
  private final IdempotencyService idempotency;
  private final TransactionTemplate tx;
  private final UserAccountService users;
  private final CollectionRequestCommandJdbcRepository commands;

  public IdempotentCollectionRequests(
      CollectionRequestService service,
      @Qualifier("rgmIdempotency") IdempotencyService idempotency,
      PlatformTransactionManager tm,
      UserAccountService users,
      CollectionRequestCommandJdbcRepository commands) {
    this.service = service;
    this.idempotency = idempotency;
    this.tx = new TransactionTemplate(tm);
    this.users = users;
    this.commands = commands;
  }

  public CollectionRequestService.Created create(
      long actorId, String key, long repositoryId, LocalDate date) {
    return tx.execute(status -> {
      // Revalidate authority before even looking at a replay, including after account revocation.
      if (!users.canMutate(users.findById(actorId).orElse(null)))
        throw new AccessDeniedException("ADMIN role required");
      String scoped = ScopedIdempotency.key("user:" + actorId, key);
      return idempotency
          .operation("CREATE_COLLECTION_REQUEST")
          .key(scoped)
          .request(Map.of("repositoryId", repositoryId))
          .execute(
              CollectionRequestService.Created.class,
              () -> {
                var bindings = commands.findBinding(scoped);
                if (!bindings.isEmpty()) {
                  var binding = bindings.getFirst();
                  if (((Number) binding.get("repository_id")).longValue() != repositoryId)
                    throw new IdempotencyConflictException(
                        new IdempotencyKey("CREATE_COLLECTION_REQUEST", scoped),
                        "retained-target",
                        "different-target");
                  long request = ((Number) binding.get("request_id")).longValue();
                  return ExecutionResult.success(
                      new CollectionRequestService.Created(
                          (java.util.UUID) binding.get("public_id"),
                          commands.taskPublicIds(request),
                          (Boolean) binding.get("coalesced")));
                }
                var created = service.manual(repositoryId, date);
                commands.insertBinding(
                    scoped, actorId, repositoryId, created.coalesced(), created.requestId());
                return ExecutionResult.success(created);
              })
          .valueOrThrow();
    });
  }
}
