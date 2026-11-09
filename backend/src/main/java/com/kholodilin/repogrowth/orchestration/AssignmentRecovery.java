package com.kholodilin.repogrowth.orchestration;

import com.kholodilin.collector.contracts.CollectorProtocol.JobRequest;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** RGM-owned atomic reassignment. A network failure alone must never call this without policy evaluation. */
@Service
@ConditionalOnProperty(name="rgm.orchestration.enabled",havingValue="true")
public class AssignmentRecovery {
 public record Reassigned(boolean changed,UUID jobId,UUID attemptId,String reason) { }
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final OrchestrationProperties properties;
 private final JsonMapper json;private final Duration minimumAge;private final int maximumAttempts;
 public AssignmentRecovery(JdbcTemplate jdbc,PlatformTransactionManager tm,OrchestrationProperties properties,JsonMapper json,Environment env) {
  this.jdbc=jdbc;this.tx=new TransactionTemplate(tm);this.properties=properties;this.json=json;
  minimumAge=env.getProperty("rgm.orchestration.recovery.minimum-age",Duration.class,Duration.ofMinutes(2));
  maximumAttempts=env.getProperty("rgm.orchestration.recovery.maximum-attempts",Integer.class,3);
  if(minimumAge.isNegative() || maximumAttempts<1 || maximumAttempts>10)throw new IllegalArgumentException("Invalid reassignment policy");
 }
 public Reassigned reassign(UUID jobId,UUID expectedAttempt,String targetNode) {
  return tx.execute(status->{
   var tasks=jdbc.queryForList("SELECT t.* FROM collection_task t JOIN collection_request r ON r.id=t.request_id WHERE t.public_id=? AND r.state='ACTIVE' FOR UPDATE OF t",jobId);
   if(tasks.isEmpty())return new Reassigned(false,jobId,expectedAttempt,"NOT_ACTIVE");var task=tasks.getFirst();
   if(!expectedAttempt.equals(task.get("current_attempt_id")))return new Reassigned(false,jobId,(UUID)task.get("current_attempt_id"),"NOT_CURRENT");
   if(!Set.of("READY","ACCEPTED","RUNNING","RETRY").contains(task.get("state")))return new Reassigned(false,jobId,expectedAttempt,"TERMINAL");
   var prior=jdbc.queryForMap("SELECT a.*,n.node_key,n.pool,d.payload FROM collection_attempt a JOIN collector_node n ON n.id=a.node_id JOIN collector_dispatch d ON d.attempt_id=a.public_id WHERE a.public_id=? FOR UPDATE OF a,d",expectedAttempt);
   if(!Set.of("ASSIGNED","ACCEPTED","RUNNING").contains(prior.get("state")))return new Reassigned(false,jobId,expectedAttempt,"NOT_ACTIVE");
   Instant now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
   if(((Timestamp)prior.get("assigned_at")).toInstant().plus(minimumAge).isAfter(now))return new Reassigned(false,jobId,expectedAttempt,"TOO_RECENT");
   int number=((Number)prior.get("attempt_no")).intValue();if(number>=maximumAttempts)return new Reassigned(false,jobId,expectedAttempt,"ATTEMPT_LIMIT");
   var target=properties.nodes().stream().filter(n->n.enabled() && n.key().equals(targetNode) && n.pool().equals(prior.get("pool")) && !n.key().equals(prior.get("node_key"))).findFirst().orElseThrow(()->new IllegalArgumentException("Reassignment requires another configured node in the same pool"));
   Long nodeId=jdbc.queryForObject("INSERT INTO collector_node(node_key,pool,endpoint,enabled) VALUES (?,?,?,true) ON CONFLICT(node_key) DO UPDATE SET pool=excluded.pool,endpoint=excluded.endpoint,enabled=true RETURNING id",Long.class,target.key(),target.pool(),OrchestrationProperties.endpoint(target));
   JobRequest original=json.readValue(prior.get("payload").toString(),JobRequest.class);UUID next=UUID.randomUUID();
   Instant deadline=now.plus(properties.jobTimeout()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
   JobRequest replacement=new JobRequest(original.schemaVersion(),original.requestId(),original.jobId(),next,original.type(),original.repository(),original.businessDate(),original.configurationRevision(),original.parameters(),original.destinationRef(),deadline,original.authorization());
   jdbc.update("UPDATE collection_attempt SET state='SUPERSEDED',completed_at=? WHERE public_id=?",Timestamp.from(now),expectedAttempt);
   jdbc.update("UPDATE collector_dispatch SET state='BLOCKED',locked_by=NULL,locked_until=NULL,last_error='SUPERSEDED' WHERE attempt_id=?",expectedAttempt);
   jdbc.update("INSERT INTO collection_attempt(public_id,task_id,attempt_no,node_id) VALUES (?,?,?,?)",next,task.get("id"),number+1,nodeId);
   jdbc.update("UPDATE collection_task SET current_attempt_id=?,state='READY',completed_at=NULL WHERE id=?",next,task.get("id"));
   jdbc.update("INSERT INTO collector_dispatch(attempt_id,endpoint,payload,deadline_at) VALUES (?,?,?::jsonb,?)",next,OrchestrationProperties.endpoint(target),json.writeValueAsString(replacement),Timestamp.from(deadline));
   return new Reassigned(true,jobId,next,"REASSIGNED");
  });
 }
}
