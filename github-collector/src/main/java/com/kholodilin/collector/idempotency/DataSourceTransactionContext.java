package com.kholodilin.collector.idempotency;

import javax.sql.DataSource;
import com.kholodilin.idempotency.spi.TransactionContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;

/** A transaction on some other datasource must never authorize this store's writes. */
public final class DataSourceTransactionContext implements TransactionContext {
    private final Probe probe;
    public DataSourceTransactionContext(DataSource datasource) {probe=new Probe(datasource);}
    private static final class Probe extends DataSourceTransactionManager {
        Probe(DataSource datasource) {super(datasource);}
        boolean ownsTransaction() {return isExistingTransaction(doGetTransaction());}
    }
    @Override public boolean isActive() {return probe.ownsTransaction();}
    @Override public void afterCommit(Runnable action) {
        if(!isActive()) throw new IllegalStateException("Owning datasource transaction is required");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {action.run();}
        });
    }
}
