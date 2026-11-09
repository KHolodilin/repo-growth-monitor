package com.kholodilin.collector.execution;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.credentials.CredentialedGitHubRequest;
import com.kholodilin.collector.queue.JobQueue;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class AssignedJobExecutorTest {
    JobQueue queue=mock(JobQueue.class);AssignedTaskCollector collector=mock(AssignedTaskCollector.class);JsonMapper json=JsonMapper.builder().build();
    ExecutionProperties properties=new ExecutionProperties(false,null,false,null,null,null,0,0,0);
    JobRequest job=new JobRequest(1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),TaskType.STATS,new RepositoryTarget("202","owner","repo"),LocalDate.now(),1,Parameters.repository(),"rgm-results",Instant.now().plusSeconds(3600));
    JobQueue.Lease lease=new JobQueue.Lease(1,"worker",1,json.writeValueAsString(job),job.deadlineAt(),"rgm",UUID.randomUUID(),0);
    @BeforeEach void setup(){when(queue.claim(anyString(),any())).thenReturn(Optional.of(lease));when(queue.renew(any(),any())).thenReturn(true);}
    @Test void completionStoresOneImmutableDeliveryEnvelope(){
        when(collector.collect(lease,job)).thenReturn(new Snapshot(Instant.now(),true,Map.of(Metric.STARS,0L),null,List.of(),List.of(),List.of(),null,null,Map.of(),List.of()));
        try(var executor=new AssignedJobExecutor(queue,collector,properties)){assertThat(executor.poll("worker")).isTrue();}
        var payload=ArgumentCaptor.forClass(String.class);verify(queue).complete(eq(lease),eq("SUCCEEDED"),any(),any(),payload.capture());var result=json.readValue(payload.getValue(),ResultDelivery.class);assertThat(result.collectorJobId()).isEqualTo(lease.publicId());assertThat(result.jobId()).isEqualTo(job.jobId());assertThat(result.snapshot().metrics()).containsEntry(Metric.STARS,0L);
    }
    @Test void quotaWaitingDoesNotSpendTechnicalRetryBudget(){
        Instant next=Instant.now().plusSeconds(60);when(collector.collect(lease,job)).thenThrow(new CredentialedGitHubRequest.Failure(429,next));
        try(var executor=new AssignedJobExecutor(queue,collector,properties)){executor.poll("worker");}verify(queue).retry(lease,next,false);verify(queue,never()).complete(any(),anyString(),any(),any(),anyString());
    }
    @Test void permanentPermissionFailureDoesNotRepeatGitHubCollection(){
        when(collector.collect(lease,job)).thenThrow(new CredentialedGitHubRequest.Failure(403));try(var executor=new AssignedJobExecutor(queue,collector,properties)){executor.poll("worker");}
        verify(queue).complete(eq(lease),eq("FAILED"),any(),any(),contains("PERMISSION_DENIED"));verify(queue,never()).retry(any(),any(),anyBoolean());
    }
    @Test void technicalFailuresUseBoundedRetriesAndSanitizedPayloads(){
        when(collector.collect(lease,job)).thenThrow(new RuntimeException("secret-must-not-be-saved"));try(var executor=new AssignedJobExecutor(queue,collector,properties)){executor.poll("worker");}verify(queue).retry(eq(lease),any(),eq(true));
        var exhausted=new JobQueue.Lease(lease.id(),lease.owner(),5,lease.payload(),lease.deadline(),lease.origin(),lease.publicId(),4);when(queue.claim(anyString(),any())).thenReturn(Optional.of(exhausted));when(collector.collect(exhausted,job)).thenThrow(new RuntimeException("secret-must-not-be-saved"));
        try(var executor=new AssignedJobExecutor(queue,collector,properties)){executor.poll("worker");}var payload=ArgumentCaptor.forClass(String.class);verify(queue).complete(eq(exhausted),eq("FAILED"),any(),any(),payload.capture());assertThat(payload.getValue()).doesNotContain("secret-must-not-be-saved");
    }

    @Test void longWorkRenewsLeaseAndLostOwnershipPreventsCompletion()throws Exception{
        var shortLease=new ExecutionProperties(false,null,false,Duration.ofMillis(90),null,Duration.ofMillis(20),0,0,0);
        var renewed=new java.util.concurrent.CountDownLatch(2);when(queue.renew(any(),any())).thenAnswer(invocation->{renewed.countDown();return true;});
        when(collector.collect(lease,job)).thenAnswer(invocation->{assertThat(renewed.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return new Snapshot(Instant.now(),true,Map.of(),null,List.of(),List.of(),List.of(),null,null,Map.of(),List.of());});
        try(var executor=new AssignedJobExecutor(queue,collector,shortLease)){executor.poll("worker");}verify(queue,atLeast(2)).renew(lease,shortLease.lease());verify(queue).complete(eq(lease),eq("SUCCEEDED"),any(),any(),anyString());
        reset(queue,collector);when(queue.claim(anyString(),any())).thenReturn(Optional.of(lease));var fenced=new java.util.concurrent.CountDownLatch(1);when(queue.renew(any(),any())).thenAnswer(invocation->{fenced.countDown();return false;});
        when(collector.collect(lease,job)).thenAnswer(invocation->{assertThat(fenced.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();Thread.sleep(20);return new Snapshot(Instant.now(),true,Map.of(),null,List.of(),List.of(),List.of(),null,null,Map.of(),List.of());});
        try(var executor=new AssignedJobExecutor(queue,collector,shortLease)){executor.poll("worker");}verify(queue,never()).complete(any(),anyString(),any(),any(),anyString());
    }
}
