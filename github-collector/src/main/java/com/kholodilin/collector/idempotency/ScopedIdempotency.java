package com.kholodilin.collector.idempotency;

import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.core.DefaultIdempotencyServiceBuilder;
import com.kholodilin.idempotency.jackson.*;
import com.kholodilin.idempotency.jdbc.*;

/** Wiring only. Acquisition, fingerprinting, replay and conflicts belong to the published starter. */
public final class ScopedIdempotency {
    private ScopedIdempotency() { }
    public static JdbcPersistenceStore validatedStore(DataSource ds) {
        new JdbcSchemaManager(ds,JdbcPersistenceStore.DEFAULT_TABLE_NAME,SchemaMode.VALIDATE).initialize();
        return new JdbcPersistenceStore(ds);
    }
    public static IdempotencyService service(DataSource ds,JdbcPersistenceStore store,Duration ttl) {
        if(ttl==null || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("Idempotency retention must be positive");
        return new DefaultIdempotencyServiceBuilder(store).fingerprintStrategy(new CanonicalJsonFingerprintStrategy())
            .serializer(new JacksonIdempotencySerializer()).transactionContext(new DataSourceTransactionContext(ds))
            .persistenceTtl(ttl).localCache(null).distributedCache(null).build();
    }
    public static String key(String trustedScope,String key) {
        if(trustedScope==null || trustedScope.isBlank()) throw new IllegalArgumentException("Trusted scope is required");
        if(key==null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw new InvalidIdempotencyKeyException();
        return new CanonicalJsonFingerprintStrategy().calculate(List.of(trustedScope,key));
    }
}
