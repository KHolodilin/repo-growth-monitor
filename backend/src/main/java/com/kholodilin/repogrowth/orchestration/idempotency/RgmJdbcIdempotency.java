package com.kholodilin.repogrowth.orchestration.idempotency;

import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.core.DefaultIdempotencyServiceBuilder;
import com.kholodilin.idempotency.jackson.CanonicalJsonFingerprintStrategy;
import com.kholodilin.idempotency.jackson.JacksonIdempotencySerializer;
import com.kholodilin.idempotency.jdbc.JdbcPersistenceStore;
import com.kholodilin.idempotency.jdbc.JdbcSchemaManager;
import com.kholodilin.idempotency.jdbc.SchemaMode;
import java.time.Duration;
import javax.sql.DataSource;

/**
 * Wiring only: the RGM product datasource backs its own idempotency store. Acquisition,
 * fingerprinting, replay and conflicts belong to the published starter.
 */
public final class RgmJdbcIdempotency {
    private RgmJdbcIdempotency() {}

    public static JdbcPersistenceStore validatedStore(DataSource ds) {
        new JdbcSchemaManager(ds, JdbcPersistenceStore.DEFAULT_TABLE_NAME, SchemaMode.VALIDATE).initialize();
        return new JdbcPersistenceStore(ds);
    }

    public static IdempotencyService service(DataSource ds, JdbcPersistenceStore store, Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero())
            throw new IllegalArgumentException("Idempotency retention must be positive");
        return new DefaultIdempotencyServiceBuilder(store)
                .fingerprintStrategy(new CanonicalJsonFingerprintStrategy())
                .serializer(new JacksonIdempotencySerializer())
                .transactionContext(new DataSourceTransactionContext(ds))
                .persistenceTtl(ttl)
                .localCache(null)
                .distributedCache(null)
                .build();
    }
}
