package com.kholodilin.collector.execution;
import com.kholodilin.collector.queue.JobQueue.Lease;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
public final class JdbcObservationStore implements ObservationStore {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final int maximum;
    public JdbcObservationStore(JdbcTemplate jdbc,TransactionTemplate tx,int maximum){this.jdbc=jdbc;this.tx=tx;this.maximum=maximum;}
    @Override public Optional<String> find(Lease lease,String key){return jdbc.queryForList("SELECT payload::text FROM collector_observation WHERE job_id=? AND observation_key=?",String.class,lease.id(),key).stream().findFirst();}
    private void lock(Lease lease){if(jdbc.queryForList("SELECT id FROM collector_job WHERE id=? AND locked_by=? AND generation=? AND state='RUNNING' AND locked_until>clock_timestamp() FOR UPDATE",Long.class,lease.id(),lease.owner(),lease.generation()).isEmpty())throw new IllegalStateException("Stale Collector lease");}
    @Override public void save(Lease lease,String key,String payload){
        int bytes=payload.getBytes(StandardCharsets.UTF_8).length;
        tx.executeWithoutResult(status->{lock(lease);long existing=jdbc.queryForObject("SELECT coalesce(sum(payload_bytes),0) FROM collector_observation WHERE job_id=? AND observation_key<>?",Long.class,lease.id(),key);
            if(existing+bytes>maximum)throw new PayloadTooLarge();
            jdbc.update("INSERT INTO collector_observation(job_id,observation_key,payload,payload_bytes) VALUES (?, ?,?::jsonb,?) ON CONFLICT(job_id,observation_key) DO UPDATE SET payload=excluded.payload,payload_bytes=excluded.payload_bytes,observed_at=clock_timestamp()",lease.id(),key,payload,bytes);
        });
    }
    @Override public void remove(Lease lease,String key){tx.executeWithoutResult(status->{lock(lease);jdbc.update("DELETE FROM collector_observation WHERE job_id=? AND observation_key=?",lease.id(),key);});}
    @Override public java.time.Instant firstObserved(Lease lease){java.sql.Timestamp at=jdbc.queryForObject("SELECT min(observed_at) FROM collector_observation WHERE job_id=?",java.sql.Timestamp.class,lease.id());return at==null?java.time.Instant.now():at.toInstant();}
    public static final class PayloadTooLarge extends RuntimeException {public PayloadTooLarge(){super("Collector observation payload exceeds the configured bound");}}
}
