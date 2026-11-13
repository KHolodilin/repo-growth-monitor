package com.kholodilin.repogrowth.orchestration;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Component
@ConditionalOnProperty(name={"rgm.orchestration.enabled","rgm.credentials.enabled","rgm.orchestration.recovery.enabled"},havingValue="true")
public class CollectorNodeMonitor {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final OrchestrationProperties nodes;private final RecoveryProperties policy;
    private final CollectorNodeClient client;private final AssignmentRecovery recovery;private final CollectorResultIngestion ingestion;private final String owner="monitor-"+UUID.randomUUID();
    public CollectorNodeMonitor(JdbcTemplate jdbc,PlatformTransactionManager tm,OrchestrationProperties nodes,RecoveryProperties policy,CollectorNodeClient client,AssignmentRecovery recovery,CollectorResultIngestion ingestion){this.jdbc=jdbc;this.tx=new TransactionTemplate(tm);this.nodes=nodes;this.policy=policy;this.client=client;this.recovery=recovery;this.ingestion=ingestion;}
    private Timestamp ts(Instant value){return Timestamp.from(value);}
    public boolean eligible(String node){return !jdbc.queryForList("SELECT node_key FROM collector_node_health WHERE node_key=? AND state='UP' AND last_success_at>clock_timestamp()-(?*interval '1 millisecond')",String.class,node,policy.nodeInterval().multipliedBy(3).toMillis()).isEmpty();}
    @Scheduled(fixedDelayString="${rgm.orchestration.recovery.tick-interval:1000}")
    public void tick(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Node HTTP cannot run in a product transaction");
        for(var node:nodes.nodes())if(node.enabled())probe(node);
        var attempts=jdbc.queryForList("""
            SELECT a.*,t.public_id AS job_id,t.state AS task_state,n.node_key,n.pool,d.endpoint,d.deadline_at
            FROM collection_attempt a JOIN collection_task t ON t.current_attempt_id=a.public_id JOIN collection_request r ON r.id=t.request_id
            JOIN collector_node n ON n.id=a.node_id JOIN collector_dispatch d ON d.attempt_id=a.public_id
            WHERE a.state IN ('ASSIGNED','ACCEPTED','RUNNING') AND r.state='ACTIVE' AND (a.last_checked_at IS NULL OR a.last_checked_at<clock_timestamp()-(?*interval '1 millisecond'))
            ORDER BY a.last_checked_at NULLS FIRST,a.assigned_at LIMIT ?
            """,policy.nodeInterval().toMillis(),policy.batchSize());
        for(var attempt:attempts)reconcile(attempt);
    }
    private void probe(OrchestrationProperties.Node node){
        Long generation=tx.execute(status->{
            jdbc.update("INSERT INTO collector_node_health(node_key) VALUES (?) ON CONFLICT DO NOTHING",node.key());
            var rows=jdbc.queryForList("SELECT generation FROM collector_node_health WHERE node_key=? AND next_check_at<=clock_timestamp() AND (locked_until IS NULL OR locked_until<=clock_timestamp()) FOR UPDATE SKIP LOCKED",Long.class,node.key());
            if(rows.isEmpty())return null;long next=rows.getFirst()+1;
            jdbc.update("UPDATE collector_node_health SET generation=?,locked_by=?,locked_until=clock_timestamp()+(?*interval '1 millisecond') WHERE node_key=?",next,owner,nodes.connectTimeout().plus(nodes.readTimeout()).plusSeconds(10).toMillis(),node.key());return next;
        });
        if(generation==null)return;boolean up;try{up=client.ready(node.key(),OrchestrationProperties.endpoint(node));}catch(RuntimeException error){up=false;}
        jdbc.update("UPDATE collector_node_health SET state=?,consecutive_failures=CASE WHEN ? THEN 0 ELSE consecutive_failures+1 END,last_checked_at=clock_timestamp(),last_success_at=CASE WHEN ? THEN clock_timestamp() ELSE last_success_at END,next_check_at=clock_timestamp()+(?*interval '1 millisecond'),locked_by=NULL,locked_until=NULL WHERE node_key=? AND locked_by=? AND generation=?",up?"UP":"DOWN",up,up,policy.nodeInterval().toMillis(),node.key(),owner,generation);
    }
    private void reconcile(Map<String,Object> attempt){
        UUID id=(UUID)attempt.get("public_id"),job=(UUID)attempt.get("job_id");String node=(String)attempt.get("node_key"),endpoint=(String)attempt.get("endpoint");
        boolean failed=false;boolean terminalKnown=Set.of("SUCCEEDED","FAILED","RESULT_UNAVAILABLE").contains(Objects.toString(attempt.get("last_collector_state"),""));
        if(eligible(node)){
            try{
                var observed=client.status(node,endpoint,(UUID)attempt.get("collector_job_id"),id);
                if(observed.isPresent()){
                    JobStatus status=observed.get();if(!status.jobId().equals(job) || !status.attemptId().equals(id))throw new IllegalStateException("Node status identity mismatch");
                    remember(attempt,status);
                    if(status.status()==JobState.SUCCEEDED || status.status()==JobState.FAILED){
                        terminalKnown=true;
                        var receipt=jdbc.queryForList("SELECT event_id FROM collector_result_receipt WHERE attempt_id=?",UUID.class,id);
                        if(!receipt.isEmpty()){client.confirm(node,endpoint,status.collectorJobId(),receipt.getFirst());return;}
                        var result=client.result(node,endpoint,status.collectorJobId());
                        if(result.isPresent()){
                            if(!result.get().collectorJobId().equals(status.collectorJobId()) || !result.get().jobId().equals(job) || !result.get().attemptId().equals(id))throw new IllegalStateException("Node result identity mismatch");
                            ingestion.apply(node,result.get());client.confirm(node,endpoint,status.collectorJobId(),result.get().eventId());
                        }else jdbc.update("UPDATE collection_attempt SET last_collector_state='RESULT_UNAVAILABLE' WHERE public_id=?",id);
                        return; // Never recollect a known terminal result because callback recovery failed.
                    }
                    Instant progress=status.updatedAt();if(((Timestamp)attempt.get("deadline_at")).toInstant().isAfter(Instant.now()) && (status.status()==JobState.ACCEPTED || status.status()==JobState.RETRY || progress.plus(policy.stalledAfter()).isAfter(Instant.now())))return;
                    failed=true;
                }else failed=true;
            }catch(RuntimeException error){failed=true;}
        }else failed=true;
        if(!failed)return;
        if(terminalKnown){jdbc.update("UPDATE collection_attempt SET last_checked_at=clock_timestamp(),last_collector_state='RESULT_UNAVAILABLE' WHERE public_id=?",id);return;}
        jdbc.update("UPDATE collection_attempt SET status_failures=status_failures+1,last_checked_at=clock_timestamp() WHERE public_id=?",id);
        int failures=jdbc.queryForObject("SELECT status_failures FROM collection_attempt WHERE public_id=?",Integer.class,id);
        var health=jdbc.queryForList("SELECT consecutive_failures FROM collector_node_health WHERE node_key=?",Integer.class,node);
        boolean confirmed=failures>=policy.failureThreshold() && (eligible(node) || (!health.isEmpty() && health.getFirst()>=policy.failureThreshold()));
        if(!confirmed)return;
        nodes.nodes().stream().filter(n->n.enabled() && n.pool().equals(attempt.get("pool")) && !n.key().equals(node) && eligible(n.key())).sorted(Comparator.comparing(OrchestrationProperties.Node::key)).findFirst().ifPresent(target->recovery.reassign(job,id,target.key()));
    }
    private void remember(Map<String,Object> expected,JobStatus status){
        tx.executeWithoutResult(transaction->{
            jdbc.queryForList("SELECT r.id FROM collection_request r JOIN collection_task t ON t.request_id=r.id WHERE t.public_id=? FOR UPDATE OF r",Long.class,status.jobId());
            var tasks=jdbc.queryForList("SELECT id,current_attempt_id,state FROM collection_task WHERE public_id=? FOR UPDATE",status.jobId());if(tasks.isEmpty() || !status.attemptId().equals(tasks.getFirst().get("current_attempt_id")))return;
            var ids=jdbc.queryForList("SELECT collector_job_id FROM collection_attempt WHERE public_id=? FOR UPDATE",UUID.class,status.attemptId());if(ids.isEmpty() || (ids.getFirst()!=null && !ids.getFirst().equals(status.collectorJobId())))throw new IllegalStateException("Collector job identity changed");
            Instant progress=status.updatedAt().isAfter(Instant.now())?Instant.now():status.updatedAt();
            jdbc.update("UPDATE collection_attempt SET collector_job_id=?,state=CASE WHEN state='ASSIGNED' THEN 'ACCEPTED' ELSE state END,last_checked_at=clock_timestamp(),status_failures=CASE WHEN last_progress_at IS NULL OR last_progress_at<? THEN 0 ELSE status_failures END,last_progress_at=?,last_collector_state=? WHERE public_id=?",status.collectorJobId(),ts(progress),ts(progress),status.status().name(),status.attemptId());
            jdbc.update("UPDATE collection_task SET state='ACCEPTED' WHERE public_id=? AND state='READY'",status.jobId());
            jdbc.update("UPDATE collector_dispatch SET state='ACKED',locked_by=NULL,locked_until=NULL,acknowledged_at=coalesce(acknowledged_at,clock_timestamp()) WHERE attempt_id=?",status.attemptId());
        });
    }
}
