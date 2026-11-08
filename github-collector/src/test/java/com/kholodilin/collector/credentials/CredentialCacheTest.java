package com.kholodilin.collector.credentials;
import com.github.benmanes.caffeine.cache.Ticker;
import com.kholodilin.collector.credentials.CredentialedGitHubRequest.Failure;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static com.kholodilin.collector.credentials.CredentialedGitHubRequest.*;
class CredentialCacheTest {
    static final class Time extends Clock implements Ticker {
        Instant now=Instant.parse("2030-01-01T00:00:00Z");long nanos;
        void advance(Duration d){now=now.plus(d);nanos+=d.toNanos();}
        public Instant instant(){return now;} public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public long read(){return nanos;}
    }
    Time time;AtomicInteger calls,version,permits;Map<UUID,JobRequest> jobs;CredentialCache cache;
    CollectorCredentialProperties properties(long maximum,int rejected) {return new CollectorCredentialProperties(false,null,maximum,rejected,null,null,null,false,List.of());}
    @BeforeEach void setup() {
        time=new Time();calls=new AtomicInteger();version=new AtomicInteger(1);permits=new AtomicInteger();jobs=new ConcurrentHashMap<>();
        cache=new CredentialCache(this::resolve,properties(100,1000),time,time);
    }
    JobRequest job(String context) {
        var job=new JobRequest(1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),TaskType.STATS,new RepositoryTarget("202","owner","repo"),LocalDate.of(2030,1,1),1,Parameters.repository(),"rgm-results",time.instant().plus(Duration.ofHours(12)),new AuthorizationScope(context,"github-main"));jobs.put(job.jobId(),job);return job;
    }
    CredentialLease resolve(String origin,CredentialRequest request) {
        calls.incrementAndGet();var job=jobs.get(request.jobId());return new CredentialLease(1,job.authorization().context(),job.authorization().credentialRef(),""+version.get(),origin+"-secret-"+version.get(),null,time.instant().plus(Duration.ofHours(12)));
    }
    CredentialedGitHubRequest client() {return new CredentialedGitHubRequest(cache,key->{permits.incrementAndGet();return ()->{};});}
    @Test void projectMappingReusesSecretsAcrossTasksButSeparatesContextsAndOrigins() {
        var a=job("context-a");var b=job("context-a");var c=job("context-c");
        assertThat(cache.get("rgm",a).token()).isEqualTo(cache.get("rgm",b).token());assertThat(calls).hasValue(1);
        cache.get("rgm",c);cache.get("other-rgm",a);assertThat(calls).hasValue(3);
        assertThat(cache.mappingSize()).isEqualTo(3);assertThat(cache.secretSize()).isEqualTo(3);
    }
    @Test void ttlIsTwoHoursAfterWriteAndReadsDoNotExtendIt() {
        var job=job("ctx");cache.get("rgm",job);version.set(2);
        time.advance(Duration.ofMinutes(119));assertThat(cache.get("rgm",job).credentialVersion()).isEqualTo("1");
        time.advance(Duration.ofMinutes(2));assertThat(cache.get("rgm",job).credentialVersion()).isEqualTo("2");assertThat(calls).hasValue(2);
    }
    @Test void serverLeaseAndTokenExpiryCapLocalTtl() {
        for(boolean expiryFirst:List.of(true,false)) {
            calls.set(0);var job=job("ctx"+expiryFirst);
            var limited=new CredentialCache((o,r)->{calls.incrementAndGet();Instant until=time.instant().plusSeconds(60);return new CredentialLease(1,job.authorization().context(),"github-main","1","secret",expiryFirst?until:null,until);},properties(100,1000),time,time);
            limited.get("rgm",job);time.advance(Duration.ofSeconds(61));limited.get("rgm",job);assertThat(calls).hasValue(2);
        }
    }
    @Test void boundedCachesEvictAndReloadWithoutMixingProjects() {
        cache=new CredentialCache(this::resolve,properties(2,1000),time,time);
        List<JobRequest> requests=new ArrayList<>();for(int i=0;i<20;i++){var job=job("project-"+i);requests.add(job);cache.get("rgm",job);}
        assertThat(cache.mappingSize()).isLessThanOrEqualTo(2);assertThat(cache.secretSize()).isLessThanOrEqualTo(2);
        assertThat(cache.get("rgm",requests.getFirst()).authorizationContext()).isEqualTo("project-0");
    }
    @Test void concurrentLoadingAndForceRefreshAreSingleFlight() throws Exception {
        var job=job("ctx");
        try(var executor=Executors.newFixedThreadPool(10)) {
            List<Callable<CredentialLease>> work=new ArrayList<>();for(int i=0;i<30;i++) work.add(()->cache.get("rgm",job));
            var results=executor.invokeAll(work);for(var result:results) assertThat(result.get().credentialVersion()).isEqualTo("1");assertThat(calls).hasValue(1);
            var rejected=results.getFirst().get();version.set(2);work.clear();for(int i=0;i<30;i++)work.add(()->cache.refreshRejected("rgm",job,rejected));
            for(var result:executor.invokeAll(work)) assertThat(result.get().credentialVersion()).isEqualTo("2");assertThat(calls).hasValue(2);
        }
    }
    @Test void staleFailureCannotInvalidateNewerVersion() {
        var job=job("ctx");var old=cache.get("rgm",job);version.set(2);cache.refreshRejected("rgm",job,old);
        cache.reject("rgm",old);assertThat(cache.get("rgm",job).credentialVersion()).isEqualTo("2");assertThat(calls).hasValue(2);
    }
    @Test void unauthorizedRefreshesOnceThroughTheGateAndQuotaErrorsNeverRefresh() {
        var job=job("ctx");AtomicInteger requests=new AtomicInteger();
        String result=client().execute("rgm",job,token->{requests.incrementAndGet();if(token.endsWith("1")){version.set(2);throw new Failure(401);}return "ok";});
        assertThat(result).isEqualTo("ok");assertThat(requests).hasValue(2);assertThat(permits).hasValue(2);assertThat(calls).hasValue(2);
        for(int status:List.of(403,429)) assertThatThrownBy(()->client().execute("rgm",job,t->{throw new Failure(status);})).isInstanceOfSatisfying(Failure.class,e->assertThat(e.status()).isEqualTo(status));
        assertThat(calls).hasValue(2);assertThat(permits).hasValue(4);
    }
    @Test void sameRejectedVersionIsQuarantinedAndNegativeResolutionIsShared() throws Exception {
        var job=job("ctx");var old=cache.get("rgm",job);
        try(var pool=Executors.newFixedThreadPool(10)) {
            List<Callable<Boolean>> work=new ArrayList<>();for(int i=0;i<20;i++) work.add(()->{try{cache.refreshRejected("rgm",job,old);return false;}catch(CredentialUnavailableException e){return true;}});
            for(var result:pool.invokeAll(work))assertThat(result.get()).isTrue();
        }
        assertThat(calls).hasValue(2);time.advance(Duration.ofSeconds(6));
        assertThatThrownBy(()->cache.get("rgm",job)).isInstanceOf(CredentialUnavailableException.class);assertThat(calls).hasValue(3);
        version.set(2);time.advance(Duration.ofSeconds(6));assertThat(cache.get("rgm",job).credentialVersion()).isEqualTo("2");
    }
    @Test void aSecond401DoesNotResolveOrRetryAgain() {
        var job=job("ctx");AtomicInteger requests=new AtomicInteger();
        assertThatThrownBy(()->client().execute("rgm",job,token->{requests.incrementAndGet();version.set(2);throw new Failure(401);})).isInstanceOf(Failure.class);
        assertThat(requests).hasValue(2);assertThat(calls).hasValue(2);assertThat(permits).hasValue(2);
        assertThatThrownBy(()->cache.get("rgm",job)).isInstanceOf(CredentialUnavailableException.class);
    }
    @Test void unavailableRgmDoesNotReuseAnExpiredSecretOrLeakExceptions() {
        var job=job("ctx");AtomicBoolean down=new AtomicBoolean();
        cache=new CredentialCache((o,r)->{if(down.get()){calls.incrementAndGet();throw new RuntimeException("secret-should-not-escape");}return resolve(o,r);},properties(100,1000),time,time);
        cache.get("rgm",job);down.set(true);time.advance(Duration.ofHours(2));
        assertThatThrownBy(()->cache.get("rgm",job)).isInstanceOf(CredentialUnavailableException.class).hasMessageNotContaining("secret-should-not-escape").hasNoCause();
        assertThatThrownBy(()->cache.get("rgm",job)).isInstanceOf(CredentialUnavailableException.class);assertThat(calls).hasValue(2);
    }
    @Test void failoverStartsWithAnIndependentEmptyCache() {
        var job=job("ctx");var a=cache.get("rgm",job);
        var nodeB=new CredentialCache(this::resolve,properties(100,1000),time,time);assertThat(nodeB.mappingSize()).isZero();
        assertThat(nodeB.get("rgm",job)).isEqualTo(a);assertThat(calls).hasValue(2);
    }
    @Test void explicitContextRevocationAndBoundedQuarantineFailClosed() {
        var a=job("a");var b=job("b");cache.get("rgm",a);cache.invalidateContext("rgm","a");
        assertThatThrownBy(()->cache.get("rgm",a)).isInstanceOf(CredentialUnavailableException.class);assertThat(cache.get("rgm",b)).isNotNull();
        cache=new CredentialCache(this::resolve,properties(100,1),time,time);var old=cache.get("rgm",a);cache.reject("rgm",old);version.set(2);
        var next=cache.get("rgm",a);cache.reject("rgm",next);assertThatThrownBy(()->cache.get("rgm",b)).isInstanceOf(CredentialUnavailableException.class);
    }
    @Test void mismatchedAuthorityAndUnscopedOrExpiredJobsNeverYieldTokens() {
        var job=job("ctx");cache=new CredentialCache((o,r)->new CredentialLease(1,"wrong-context","github-main","1","secret",null,time.instant().plusSeconds(60)),properties(100,1000),time,time);
        assertThatThrownBy(()->cache.get("rgm",job)).isInstanceOf(CredentialUnavailableException.class);
        var unscoped=new JobRequest(1,job.requestId(),job.jobId(),job.attemptId(),job.type(),job.repository(),job.businessDate(),1,job.parameters(),job.destinationRef(),job.deadlineAt());
        assertThatThrownBy(()->cache.get("rgm",unscoped)).isInstanceOf(CredentialUnavailableException.class);
        time.advance(Duration.ofHours(13));assertThatThrownBy(()->cache.get("rgm",job)).isInstanceOf(CredentialUnavailableException.class);
    }
}
