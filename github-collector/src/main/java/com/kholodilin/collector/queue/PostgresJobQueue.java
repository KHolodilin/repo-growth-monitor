package com.kholodilin.collector.queue;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresJobQueue implements JobQueue {
    public static final String CLAIM_SQL = """
        SELECT id FROM collector_job
        WHERE (state IN ('READY','RETRY') AND next_attempt_at <= clock_timestamp())
           OR (state='RUNNING' AND locked_until <= clock_timestamp())
        ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
        """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final HistoryPartitions partitions;
    public PostgresJobQueue(JdbcTemplate jdbc, TransactionTemplate tx, HistoryPartitions partitions) {
        this.jdbc=jdbc; this.tx=tx; this.partitions=partitions;
    }
    private static Timestamp ts(Instant value) { return Timestamp.from(Objects.requireNonNull(value)); }
    private static long millis(Duration duration) {
        long ms=duration.toMillis(); if(ms<=0 || ms>3_600_000) throw new IllegalArgumentException("Lease must be 1ms..1h"); return ms;
    }
    private static String owner(String value) {
        if(value==null || value.isBlank() || value.length()>128) throw new IllegalArgumentException("Invalid owner"); return value;
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public Accepted accept(Submission s) {
        owner(s.origin()); owner(s.destination()); Objects.requireNonNull(s.jobId()); Objects.requireNonNull(s.attemptId());
        Objects.requireNonNull(s.payload()); Objects.requireNonNull(s.deadline());
        return tx.execute(status -> {
            // JSONB normalizes object key ordering before hashing. Destination/deadline are also immutable.
            String normalized=jdbc.queryForObject("SELECT jsonb_build_array(CAST(? AS jsonb),?::text,extract(epoch FROM ?::timestamptz))::text",String.class,s.payload(),s.destination(),ts(s.deadline()));
            String digest=hash(normalized);
            int inserted=jdbc.update("INSERT INTO collector_job_identity(public_id,origin_id,external_job_id,external_attempt_id,payload_hash) VALUES (?,?,?,?,?) ON CONFLICT(origin_id,external_attempt_id) DO NOTHING",
                UUID.randomUUID(),s.origin(),s.jobId(),s.attemptId(),digest);
            var rows=jdbc.queryForList("SELECT * FROM collector_job_identity WHERE origin_id=? AND external_attempt_id=? FOR UPDATE",s.origin(),s.attemptId());
            var row=rows.getFirst(); long id=((Number)row.get("id")).longValue();
            if(!digest.equals(row.get("payload_hash")) || !s.jobId().equals(row.get("external_job_id"))) throw new AttemptConflictException();
            if(inserted==1) jdbc.update("INSERT INTO collector_job(id,payload,destination_ref,deadline_at) VALUES (?,?::jsonb,?,?)",id,s.payload(),s.destination(),ts(s.deadline()));
            return new Accepted(id,(UUID)row.get("public_id"),(String)row.get("state"),inserted==0,((Timestamp)row.get("accepted_at")).toInstant());
        });
    }
    @Override public Optional<Lease> claim(String worker, Duration duration) {
        owner(worker); long ms=millis(duration);
        return tx.execute(status -> {
            List<Long> ids=jdbc.queryForList(CLAIM_SQL,Long.class); if(ids.isEmpty()) return Optional.empty(); long id=ids.getFirst();
            jdbc.update("UPDATE collector_execution SET state='LEASE_EXPIRED',finished_at=clock_timestamp() WHERE job_id=? AND state='RUNNING'",id);
            jdbc.update("UPDATE collector_job SET state='RUNNING',execution_no=execution_no+1,generation=generation+1,locked_by=?,locked_until=clock_timestamp()+(? * interval '1 millisecond'),updated_at=clock_timestamp() WHERE id=?",worker,ms,id);
            jdbc.update("UPDATE collector_job_identity SET state='RUNNING' WHERE id=?",id);
            jdbc.update("INSERT INTO collector_execution(job_id,execution_no,generation,worker_id,state) SELECT id,execution_no,generation,locked_by,'RUNNING' FROM collector_job WHERE id=?",id);
            return Optional.of(jdbc.queryForObject("SELECT j.*,i.origin_id,i.public_id AS collector_public_id FROM collector_job j JOIN collector_job_identity i ON i.id=j.id WHERE j.id=?",(r,n)->new Lease(id,worker,r.getLong("generation"),r.getString("payload"),r.getTimestamp("deadline_at").toInstant(),r.getString("origin_id"),r.getObject("collector_public_id",UUID.class),r.getInt("failure_count")),id));
        });
    }
    private boolean lock(Lease lease) {
        return !jdbc.queryForList("SELECT id FROM collector_job WHERE id=? AND state='RUNNING' AND locked_by=? AND generation=? AND locked_until>clock_timestamp() FOR UPDATE",Long.class,lease.id(),lease.owner(),lease.generation()).isEmpty();
    }
    @Override public boolean renew(Lease lease,Duration duration) {
        long ms=millis(duration);
        return jdbc.update("UPDATE collector_job SET locked_until=clock_timestamp()+(? * interval '1 millisecond'),updated_at=clock_timestamp() WHERE id=? AND state='RUNNING' AND locked_by=? AND generation=? AND locked_until>clock_timestamp()",ms,lease.id(),lease.owner(),lease.generation())==1;
    }
    @Override public boolean retry(Lease lease,Instant next) {return retry(lease,next,true);}
    @Override public boolean retry(Lease lease,Instant next,boolean countFailure) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            if(!lock(lease)) return false;
            jdbc.update("UPDATE collector_execution SET state='RETRY',finished_at=clock_timestamp() WHERE job_id=? AND generation=?",lease.id(),lease.generation());
            jdbc.update("UPDATE collector_job SET state='RETRY',locked_by=NULL,locked_until=NULL,next_attempt_at=?,failure_count=failure_count+?,updated_at=clock_timestamp() WHERE id=?",ts(next),countFailure?1:0,lease.id());
            jdbc.update("UPDATE collector_job_identity SET state='RETRY' WHERE id=?",lease.id()); return true;
        }));
    }
    @Override public boolean complete(Lease lease,String state,UUID resultId,UUID eventId,String payload) {
        if(!Set.of("SUCCEEDED","FAILED","CANCELLED").contains(state)) throw new IllegalArgumentException("Terminal state required");
        return Boolean.TRUE.equals(tx.execute(status -> {
            if(!lock(lease)) return false;
            jdbc.update("INSERT INTO collector_result(public_id,event_id,job_id,payload,payload_hash) VALUES (?,?,?,?::jsonb,?)",resultId,eventId,lease.id(),payload,hash(payload));
            jdbc.update("INSERT INTO collector_delivery(result_id,destination_ref) SELECT r.id,j.destination_ref FROM collector_result r JOIN collector_job j ON j.id=r.job_id WHERE j.id=?",lease.id());
            jdbc.update("UPDATE collector_execution SET state=?,finished_at=clock_timestamp() WHERE job_id=? AND generation=?",state,lease.id(),lease.generation());
            jdbc.update("UPDATE collector_job SET state=?,locked_by=NULL,locked_until=NULL,updated_at=clock_timestamp() WHERE id=?",state,lease.id());
            jdbc.update("UPDATE collector_job_identity SET state=? WHERE id=?",state,lease.id()); return true;
        }));
    }
    @Override public Optional<Delivery> claimDelivery(String worker,Duration duration) {
        owner(worker); long ms=millis(duration);
        return tx.execute(status -> {
            var ids=jdbc.queryForList("SELECT id FROM collector_delivery WHERE (state IN ('READY','RETRY') AND next_attempt_at<=clock_timestamp()) OR (state='SENDING' AND locked_until<=clock_timestamp()) ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",Long.class);
            if(ids.isEmpty()) return Optional.empty(); long id=ids.getFirst();
            jdbc.update("UPDATE collector_delivery SET state='SENDING',generation=generation+1,locked_by=?,locked_until=clock_timestamp()+(? * interval '1 millisecond') WHERE id=?",worker,ms,id);
            return Optional.of(jdbc.queryForObject("SELECT d.*,r.payload,r.job_id,i.origin_id,j.generation AS job_generation FROM collector_delivery d JOIN collector_result r ON r.id=d.result_id JOIN collector_job_identity i ON i.id=r.job_id JOIN collector_job j ON j.id=r.job_id WHERE d.id=?",(r,n)->new Delivery(id,worker,r.getLong("generation"),r.getString("destination_ref"),r.getString("payload"),r.getString("origin_id"),r.getLong("job_id"),r.getLong("job_generation")),id));
        });
    }
    @Override public boolean acknowledge(Delivery d) {
        return jdbc.update("UPDATE collector_delivery SET state='DELIVERED',delivered_at=clock_timestamp(),locked_by=NULL,locked_until=NULL WHERE id=? AND state='SENDING' AND locked_by=? AND generation=? AND locked_until>clock_timestamp()",d.id(),d.owner(),d.generation())==1;
    }
    @Override public boolean retryDelivery(Delivery d,Instant next) {
        return jdbc.update("UPDATE collector_delivery SET state='RETRY',next_attempt_at=?,locked_by=NULL,locked_until=NULL WHERE id=? AND state='SENDING' AND locked_by=? AND generation=? AND locked_until>clock_timestamp()",ts(next),d.id(),d.owner(),d.generation())==1;
    }
    @Override public boolean deadLetter(Delivery delivery,String code) {
        if(!java.util.Set.of("DESTINATION_UNAVAILABLE","REJECTED","RETRY_EXHAUSTED").contains(code))throw new IllegalArgumentException("Invalid delivery error");
        return jdbc.update("UPDATE collector_delivery SET state='DEAD',locked_by=NULL,locked_until=NULL,dead_letter_at=clock_timestamp(),error_code=? WHERE id=? AND state='SENDING' AND locked_by=? AND generation=? AND locked_until>clock_timestamp()",code,delivery.id(),delivery.owner(),delivery.generation())==1;
    }
    @Override public Optional<com.kholodilin.collector.contracts.CollectorProtocol.JobStatus> statusByAttempt(UUID attempt,String origin){var ids=jdbc.queryForList("SELECT public_id FROM collector_job_identity WHERE external_attempt_id=? AND origin_id=?",UUID.class,attempt,origin);return ids.isEmpty()?Optional.empty():status(ids.getFirst(),origin);}
    @Override public java.util.Map<String,Object> diagnostics(String origin){
        java.util.Map<String,Object> values=new java.util.LinkedHashMap<>();
        values.put("pending",jdbc.queryForObject("SELECT count(*) FROM collector_job j JOIN collector_job_identity i ON i.id=j.id WHERE i.origin_id=? AND j.state IN ('READY','RETRY','RUNNING')",Long.class,origin));
        values.put("oldestQueueAgeSeconds",jdbc.queryForObject("SELECT coalesce(extract(epoch FROM clock_timestamp()-min(i.accepted_at)),0)::bigint FROM collector_job j JOIN collector_job_identity i ON i.id=j.id WHERE i.origin_id=? AND j.state IN ('READY','RETRY','RUNNING')",Long.class,origin));
        values.put("deadDeliveries",jdbc.queryForObject("SELECT count(*) FROM collector_delivery d JOIN collector_result r ON r.id=d.result_id JOIN collector_job_identity i ON i.id=r.job_id WHERE i.origin_id=? AND d.state='DEAD'",Long.class,origin));return values;
    }
    @Override public Optional<com.kholodilin.collector.contracts.CollectorProtocol.JobStatus> status(UUID id,String origin) {
        return tx.execute(state->{
            var rows=jdbc.queryForList("SELECT * FROM collector_job_identity WHERE public_id=? AND origin_id=? FOR SHARE",id,origin);
            if(rows.isEmpty())return Optional.empty();var identity=rows.getFirst();
            String storedState=(String)identity.get("state");var status=com.kholodilin.collector.contracts.CollectorProtocol.JobState.valueOf(storedState.equals("READY")?"ACCEPTED":storedState);
            UUID resultId=null;java.time.Instant updated=((Timestamp)identity.get("accepted_at")).toInstant();var stored=find(id);
            if(stored.isPresent() && stored.get().snapshot()!=null){var tree=toTree(stored.get().snapshot());var publicResult=tree.path("result").path("public_id");if(!publicResult.isMissingNode() && !publicResult.isNull())resultId=UUID.fromString(publicResult.asText());var time=tree.path("job").path("updated_at");if(time.isTextual())updated=java.time.OffsetDateTime.parse(time.asText()).toInstant();}
            return Optional.of(new com.kholodilin.collector.contracts.CollectorProtocol.JobStatus(1,id,(UUID)identity.get("external_job_id"),(UUID)identity.get("external_attempt_id"),status,updated,resultId,null));
        });
    }
    private tools.jackson.databind.JsonNode toTree(String value){return tools.jackson.databind.json.JsonMapper.builder().build().readTree(value);}
    @Override public Optional<String> result(UUID id,String origin) {
        return tx.execute(state->{
            if(jdbc.queryForList("SELECT id FROM collector_job_identity WHERE public_id=? AND origin_id=? FOR SHARE",Long.class,id,origin).isEmpty())return Optional.empty();
            var stored=find(id);if(stored.isEmpty() || stored.get().snapshot()==null)return Optional.empty();var result=toTree(stored.get().snapshot()).path("result").path("payload");
            return result.isMissingNode() || result.isNull()?Optional.empty():Optional.of(result.toString());
        });
    }
    @Override public boolean confirmDelivery(UUID id,String origin,UUID eventId) {
        return jdbc.update("UPDATE collector_delivery d SET state='DELIVERED',locked_by=NULL,locked_until=NULL,delivered_at=clock_timestamp() FROM collector_result r JOIN collector_job_identity i ON i.id=r.job_id WHERE d.result_id=r.id AND i.public_id=? AND i.origin_id=? AND r.event_id=?",id,origin,eventId)==1;
    }
    @Override public boolean requeueDelivery(UUID id,String origin) {
        return jdbc.update("UPDATE collector_delivery d SET state='READY',locked_by=NULL,locked_until=NULL,generation=generation+1,next_attempt_at=clock_timestamp(),dead_letter_at=NULL,error_code=NULL FROM collector_result r JOIN collector_job_identity i ON i.id=r.job_id WHERE d.result_id=r.id AND i.public_id=? AND i.origin_id=? AND d.state IN ('DEAD','RETRY')",id,origin)==1;
    }
    @Override public boolean archive(long id,long generation) {
        // DDL is never held inside a queue claim transaction. A rollover cannot strand active work.
        partitions.ensureCurrentAndNext();
        return Boolean.TRUE.equals(tx.execute(status -> {
            var ids=jdbc.queryForList("SELECT j.id FROM collector_job j JOIN collector_result r ON r.job_id=j.id JOIN collector_delivery d ON d.result_id=r.id WHERE j.id=? AND j.generation=? AND j.state IN ('SUCCEEDED','FAILED','CANCELLED') AND d.state='DELIVERED' FOR UPDATE OF j,d SKIP LOCKED",Long.class,id,generation);
            if(ids.isEmpty()) return false;
            Instant at=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
            jdbc.update("""
                INSERT INTO collector_job_history(archived_at,job_id,snapshot)
                SELECT ?,j.id,jsonb_build_object('job',to_jsonb(j),'result',to_jsonb(r),'delivery',to_jsonb(d),
                  'executions',(SELECT coalesce(jsonb_agg(to_jsonb(e) ORDER BY execution_no),'[]'::jsonb) FROM collector_execution e WHERE e.job_id=j.id))
                FROM collector_job j JOIN collector_result r ON r.job_id=j.id JOIN collector_delivery d ON d.result_id=r.id WHERE j.id=?
                """,ts(at),id);
            jdbc.update("UPDATE collector_job_identity SET archived_at=? WHERE id=?",ts(at),id);
            jdbc.update("DELETE FROM collector_delivery WHERE result_id IN (SELECT id FROM collector_result WHERE job_id=?)",id);
            jdbc.update("DELETE FROM collector_result WHERE job_id=?",id);
            jdbc.update("DELETE FROM collector_observation WHERE job_id=?",id);
            jdbc.update("DELETE FROM collector_execution WHERE job_id=?",id);
            jdbc.update("DELETE FROM collector_job WHERE id=?",id); return true;
        }));
    }
    @Override public Optional<Stored> find(UUID publicId) {
        // One snapshot transaction prevents a read racing the active -> archive move.
        return tx.execute(status -> {
            var rows=jdbc.queryForList("SELECT * FROM collector_job_identity WHERE public_id=? FOR SHARE",publicId);
            if(rows.isEmpty()) return Optional.empty(); var row=rows.getFirst();
            long id=((Number)row.get("id")).longValue(); Timestamp at=(Timestamp)row.get("archived_at");
            List<String> snapshots=at==null ? jdbc.queryForList("SELECT jsonb_build_object('job',to_jsonb(j),'result',to_jsonb(r))::text FROM collector_job j LEFT JOIN collector_result r ON r.job_id=j.id WHERE j.id=?",String.class,id)
                : jdbc.queryForList("SELECT snapshot::text FROM collector_job_history WHERE archived_at=? AND job_id=?",String.class,at,id);
            return Optional.of(new Stored(new Accepted(id,publicId,(String)row.get("state"),true,((Timestamp)row.get("accepted_at")).toInstant()),at==null?null:at.toInstant(),snapshots.isEmpty()?null:snapshots.getFirst()));
        });
    }
}
