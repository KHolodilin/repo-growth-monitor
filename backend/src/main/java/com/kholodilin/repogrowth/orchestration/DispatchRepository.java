package com.kholodilin.repogrowth.orchestration;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Repository
@ConditionalOnProperty(name="rgm.orchestration.enabled",havingValue="true")
public class DispatchRepository {
    public record Claim(long id,String owner,long generation,String nodeKey,String endpoint,JobRequest request,int sendCount) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final OrchestrationProperties properties;
    public DispatchRepository(JdbcTemplate jdbc,PlatformTransactionManager tm,JsonMapper json,OrchestrationProperties properties) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(tm);this.json=json;this.properties=properties;
    }
    public Optional<Claim> claim(String owner) {
        if(owner==null || owner.isBlank() || owner.length()>128) throw new IllegalArgumentException("Invalid dispatch owner");
        return tx.execute(status -> {
            var ids=jdbc.queryForList("""
                SELECT id FROM collector_dispatch WHERE (state IN ('READY','RETRY') AND next_attempt_at<=clock_timestamp())
                  OR (state='SENDING' AND locked_until<=clock_timestamp())
                ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """,Long.class);
            if(ids.isEmpty()) return Optional.empty(); long id=ids.getFirst();
            jdbc.update("UPDATE collector_dispatch SET state='SENDING',send_count=send_count+1,generation=generation+1,locked_by=?,locked_until=clock_timestamp()+(? * interval '1 millisecond') WHERE id=?",owner,properties.dispatchLease().toMillis(),id);
            return Optional.of(jdbc.queryForObject("SELECT d.*,n.node_key FROM collector_dispatch d JOIN collection_attempt a ON a.public_id=d.attempt_id JOIN collector_node n ON n.id=a.node_id WHERE d.id=?",(r,n)->new Claim(id,owner,r.getLong("generation"),r.getString("node_key"),r.getString("endpoint"),json.readValue(r.getString("payload"),JobRequest.class),r.getInt("send_count")),id));
        });
    }
    private boolean lock(Claim claim) {
        // Result application serializes request aggregate completion before locking its task.
        jdbc.queryForList("SELECT r.id FROM collection_request r JOIN collection_task t ON t.request_id=r.id WHERE t.public_id=? FOR UPDATE OF r",Long.class,claim.request().jobId());
        // Recovery and result application take the task lock first; keep the same lock order.
        jdbc.queryForList("SELECT id FROM collection_task WHERE public_id=? FOR UPDATE",Long.class,claim.request().jobId());
        return !jdbc.queryForList("SELECT id FROM collector_dispatch WHERE id=? AND state='SENDING' AND generation=? AND locked_by=? AND locked_until>clock_timestamp() FOR UPDATE",Long.class,claim.id(),claim.generation(),claim.owner()).isEmpty();
    }
    public boolean acknowledge(Claim claim,JobAccepted accepted) {
        if(!accepted.jobId().equals(claim.request().jobId()) || !accepted.attemptId().equals(claim.request().attemptId())) throw new IllegalArgumentException("Collector acceptance identity mismatch");
        return Boolean.TRUE.equals(tx.execute(status -> {
            if(!lock(claim)) return false;
            var known=jdbc.queryForList("SELECT collector_job_id FROM collection_attempt WHERE public_id=? AND collector_job_id IS NOT NULL",UUID.class,accepted.attemptId());
            if(!known.isEmpty() && !known.getFirst().equals(accepted.collectorJobId())) throw new IllegalArgumentException("Collector acceptance local identity changed");
            // Terminal callback can commit before this response: never downgrade its state.
            jdbc.update("UPDATE collection_attempt SET state=CASE WHEN state='ASSIGNED' THEN 'ACCEPTED' ELSE state END,collector_job_id=coalesce(collector_job_id,?),accepted_at=coalesce(accepted_at,?) WHERE public_id=?",accepted.collectorJobId(),Timestamp.from(accepted.acceptedAt()),accepted.attemptId());
            jdbc.update("UPDATE collection_task SET state='ACCEPTED' WHERE public_id=? AND current_attempt_id=? AND state='READY'",accepted.jobId(),accepted.attemptId());
            jdbc.update("UPDATE collector_dispatch SET state='ACKED',locked_by=NULL,locked_until=NULL,acknowledged_at=clock_timestamp(),last_error=NULL WHERE id=?",claim.id()); return true;
        }));
    }
    public boolean failed(Claim claim,String error,boolean retryable) {
        // Persist bounded categories only, never exception bodies, credentials or response contents.
        if(!Set.of("TRANSPORT","HTTP_RETRY","HTTP_REJECTED","INVALID_ACCEPTANCE","UNTRUSTED_NODE").contains(error)) throw new IllegalArgumentException("Unknown dispatch error");
        long delay=Math.min(300_000L,properties.retryDelay().toMillis() * (1L << Math.min(claim.sendCount()-1,6)));
        return jdbc.update("UPDATE collector_dispatch SET state=?,last_error=?,next_attempt_at=clock_timestamp()+(? * interval '1 millisecond'),locked_by=NULL,locked_until=NULL WHERE id=? AND state='SENDING' AND generation=? AND locked_by=? AND locked_until>clock_timestamp()",retryable?"RETRY":"BLOCKED",error,delay,claim.id(),claim.generation(),claim.owner())==1;
    }
    public boolean expired(Claim claim) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            if(!lock(claim)) return false;
            // A callback receipt wins even if the dispatch response was lost. Only pending work expires.
            jdbc.update("UPDATE collection_attempt SET state='FAILED',completed_at=clock_timestamp() WHERE public_id=? AND state IN ('ASSIGNED','ACCEPTED')",claim.request().attemptId());
            jdbc.update("UPDATE collection_task SET state='FAILED',completed_at=clock_timestamp() WHERE public_id=? AND current_attempt_id=? AND state IN ('READY','ACCEPTED')",claim.request().jobId(),claim.request().attemptId());
            jdbc.update("UPDATE collector_dispatch SET state='EXPIRED',locked_by=NULL,locked_until=NULL,last_error='DEADLINE_EXCEEDED' WHERE id=?",claim.id());
            jdbc.update("""
                UPDATE collection_request r SET state=CASE WHEN EXISTS(SELECT 1 FROM collection_task t WHERE t.request_id=r.id AND t.state='FAILED') THEN 'FAILED' ELSE 'SUCCEEDED' END,completed_at=clock_timestamp()
                WHERE r.public_id=? AND r.state='ACTIVE' AND NOT EXISTS(SELECT 1 FROM collection_task t WHERE t.request_id=r.id AND t.state IN ('READY','ACCEPTED','RUNNING','RETRY'))
                """,claim.request().requestId()); return true;
        }));
    }
}
