package com.kholodilin.collector.execution;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.credentials.*;
import com.kholodilin.collector.queue.JobQueue;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.json.JsonMapper;
public final class AssignedJobExecutor implements AutoCloseable {
    private final JobQueue queue;private final AssignedTaskCollector collector;private final ExecutionProperties properties;
    private final JsonMapper json=JsonMapper.builder().build();private final ScheduledExecutorService heartbeats=Executors.newScheduledThreadPool(1,r->{Thread t=new Thread(r,"collector-lease-heartbeat");t.setDaemon(true);return t;});
    public AssignedJobExecutor(JobQueue queue,AssignedTaskCollector collector,ExecutionProperties properties){this.queue=queue;this.collector=collector;this.properties=properties;}
    public boolean poll(String owner){
        var pending=queue.claim(owner,properties.lease());if(pending.isEmpty())return false;var lease=pending.get();
        AtomicBoolean live=new AtomicBoolean(true);
        var heartbeat=heartbeats.scheduleWithFixedDelay(()->{try{if(!queue.renew(lease,properties.lease()))live.set(false);}catch(RuntimeException e){live.set(false);}},properties.lease().toMillis()/3,properties.lease().toMillis()/3,TimeUnit.MILLISECONDS);
        JobRequest job;
        try{job=json.readValue(lease.payload(),JobRequest.class);}catch(RuntimeException invalid){queue.retry(lease,Instant.now().plusSeconds(60),true);heartbeat.cancel(false);return true;}
        try{
            if(!job.deadlineAt().isAfter(Instant.now())){finish(lease,job,null,new Failure(ErrorCode.DEADLINE_EXCEEDED,false,"Assignment deadline exceeded",null),live);return true;}
            Snapshot snapshot=collector.collect(lease,job);
            if(!live.get())return true;
            var delivery=new ResultDelivery(1,UUID.randomUUID(),UUID.randomUUID(),lease.publicId(),job.jobId(),job.attemptId(),job.type(),job.repository().githubId(),JobState.SUCCEEDED,snapshot,null);
            String payload=json.writeValueAsString(delivery);if(payload.getBytes(StandardCharsets.UTF_8).length>properties.maximumPayloadBytes())throw new JdbcObservationStore.PayloadTooLarge();
            queue.complete(lease,"SUCCEEDED",delivery.resultId(),delivery.eventId(),payload);
        }catch(CredentialedGitHubRequest.Failure error){
            if((error.status()==429 || error.status()==403) && error.retryAt()!=null)queue.retry(lease,later(error.retryAt()),false);
            else if(error.status()==413)finish(lease,job,null,new Failure(ErrorCode.INVALID_REQUEST,false,"Source response exceeds payload limit; narrow the assignment",null),live);
            else if(error.status()==401 || error.status()==403 || error.status()==404)finish(lease,job,null,new Failure(error.status()==401?ErrorCode.AUTHENTICATION_FAILED:error.status()==404?ErrorCode.SOURCE_NOT_FOUND:ErrorCode.PERMISSION_DENIED,false,"GitHub rejected the assigned collection",null),live);
            else retryOrFail(lease,job,live);
        }catch(JdbcObservationStore.PayloadTooLarge tooLarge){finish(lease,job,null,new Failure(ErrorCode.INVALID_REQUEST,false,"Collection exceeds payload/page limits; narrow or split the assigned work",null),live);}
        catch(RuntimeException failure){retryOrFail(lease,job,live);}
        finally{heartbeat.cancel(false);}
        return true;
    }
    private Instant later(Instant retry){Instant now=Instant.now();return retry.isAfter(now)?retry:now.plusSeconds(1);}
    private void retryOrFail(JobQueue.Lease lease,JobRequest job,AtomicBoolean live){
        if(!live.get())return;
        if(lease.failures()+1>=properties.maximumFailures())finish(lease,job,null,new Failure(ErrorCode.SOURCE_UNAVAILABLE,false,"Collection failed after bounded retries",null),live);
        else queue.retry(lease,Instant.now().plusSeconds(Math.min(300,5L<<Math.min(lease.failures(),5))),true);
    }
    private void finish(JobQueue.Lease lease,JobRequest job,Snapshot snapshot,Failure failure,AtomicBoolean live){
        if(!live.get())return;var delivery=new ResultDelivery(1,UUID.randomUUID(),UUID.randomUUID(),lease.publicId(),job.jobId(),job.attemptId(),job.type(),job.repository().githubId(),JobState.FAILED,null,failure);
        queue.complete(lease,"FAILED",delivery.resultId(),delivery.eventId(),json.writeValueAsString(delivery));
    }
    @Override public void close(){heartbeats.shutdownNow();}
}
