package com.kholodilin.repogrowth.orchestration;

import javax.sql.DataSource;
import com.kholodilin.collector.idempotency.ScopedIdempotency;
import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.jdbc.JdbcPersistenceStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.time.Duration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="rgm.orchestration.enabled",havingValue="true")
public class RequestIdempotencyConfiguration {
    @Bean(name="rgmIdempotencyStore",defaultCandidate=false)
    @DependsOnDatabaseInitialization
    JdbcPersistenceStore rgmIdempotencyStore(DataSource ds) {return ScopedIdempotency.validatedStore(ds);}
    @Bean(name="rgmIdempotency",defaultCandidate=false)
    IdempotencyService rgmIdempotency(DataSource ds,@Qualifier("rgmIdempotencyStore") JdbcPersistenceStore store,Environment env) {
        return ScopedIdempotency.service(ds,store,env.getProperty("rgm.orchestration.idempotency-retention",Duration.class,Duration.ofDays(7)));
    }
}
