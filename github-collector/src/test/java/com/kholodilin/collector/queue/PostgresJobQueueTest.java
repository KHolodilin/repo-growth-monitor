package com.kholodilin.collector.queue;

import com.kholodilin.collector.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static com.kholodilin.collector.queue.JobQueue.*;

class PostgresJobQueueTest {
    static PostgreSQLContainer pg=new PostgreSQLContainer("postgres:18.6");
    static DataSource ds; static JdbcTemplate jdbc; static PostgresJobQueue queue; static HistoryPartitions partitions;
    static Duration minute=Duration.ofMinutes(1);
    @BeforeAll static void start() throws Exception {
        pg.start(); ds=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
        jdbc=new JdbcTemplate(ds); SpringLiquibase lb=new SpringLiquibase(); lb.setDataSource(ds);
        lb.setChangeLog("classpath:collector/db/changelog/master.yaml"); lb.afterPropertiesSet(); lb.afterPropertiesSet();
        partitions=new HistoryPartitions(jdbc); partitions.ensureCurrentAndNext();
        queue=new PostgresJobQueue(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),partitions);
    }
    @AfterAll static void stop() { pg.stop(); }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE collector_job_identity,collector_job,collector_execution,collector_result,collector_delivery,collector_job_history RESTART IDENTITY");
    }
    Submission submission() { return new Submission("rgm",UUID.randomUUID(),UUID.randomUUID(),"{\"a\":1,\"b\":2}","rgm-results",Instant.parse("2027-01-01T00:00:00Z")); }
    Lease acceptClaim() { queue.accept(submission()); return queue.claim("node-a",minute).orElseThrow(); }
    void finish(Lease lease) { assertThat(queue.complete(lease,"SUCCEEDED",UUID.randomUUID(),UUID.randomUUID(),"{\"data\":42}")).isTrue(); }
    @Test void acceptanceIsAtomicAndPersistentAcrossArchive() {
        Submission s=submission(); Accepted accepted=queue.accept(s);
        assertThat(queue.accept(new Submission(s.origin(),s.jobId(),s.attemptId(),"{\"b\":2,\"a\":1}",s.destination(),s.deadline())).replay()).isTrue();
        assertThatThrownBy(()->queue.accept(new Submission(s.origin(),s.jobId(),s.attemptId(),"{}",s.destination(),s.deadline()))).isInstanceOf(IllegalArgumentException.class);
        var lease=queue.claim("a",minute).orElseThrow(); finish(lease);
        assertThat(queue.archive(lease.id(),lease.generation())).isFalse();
        assertThat(queue.acknowledge(queue.claimDelivery("sender",minute).orElseThrow())).isTrue();
        assertThat(queue.archive(lease.id(),lease.generation()+1)).isFalse();
        assertThat(queue.archive(lease.id(),lease.generation())).isTrue();
        assertThat(queue.archive(lease.id(),lease.generation())).isFalse();
        assertThat(queue.accept(s)).extracting(Accepted::id,Accepted::publicId,Accepted::state,Accepted::replay).containsExactly(accepted.id(),accepted.publicId(),"SUCCEEDED",true);
        assertThat(queue.claim("a",minute)).isEmpty();
        var stored=queue.find(accepted.publicId()).orElseThrow();
        assertThat(stored.archivedAt()).isNotNull(); assertThat(stored.snapshot()).contains("executions","data","DELIVERED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collector_job",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collector_execution",Long.class)).isZero();
    }
    @Test void badPayloadRollsBackAcceptanceAndCompletion() {
        var s=submission();
        assertThatThrownBy(()->queue.accept(new Submission(s.origin(),s.jobId(),s.attemptId(),"not-json",s.destination(),s.deadline()))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collector_job_identity",Long.class)).isZero();
        var lease=acceptClaim();
        assertThatThrownBy(()->queue.complete(lease,"FAILED",UUID.randomUUID(),UUID.randomUUID(),"not-json")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collector_result",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM collector_job",String.class)).isEqualTo("RUNNING"); finish(lease);
    }
    @Test void claimsAreExclusiveAndSkipLocked() throws Exception {
        for(int i=0;i<20;i++) queue.accept(submission());
        try(var pool=Executors.newFixedThreadPool(6)) {
            List<Callable<Optional<Lease>>> work=new ArrayList<>();
            for(int i=0;i<30;i++) work.add(()->queue.claim(UUID.randomUUID().toString(),minute));
            Set<Long> ids=new HashSet<>();
            for(var future:pool.invokeAll(work)) future.get().ifPresent(l->assertThat(ids.add(l.id())).isTrue());
            assertThat(ids).hasSize(20);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collector_execution",Long.class)).isEqualTo(20);
    }
    @Test void concurrentAcceptanceCreatesOneIdentity() throws Exception {
        var s=submission();
        try(var pool=Executors.newFixedThreadPool(5)) {
            List<Callable<Accepted>> tasks=new ArrayList<>(); for(int i=0;i<10;i++) tasks.add(()->queue.accept(s));
            Set<UUID> ids=new HashSet<>(); int fresh=0;
            for(var f:pool.invokeAll(tasks)) {var a=f.get(); ids.add(a.publicId()); if(!a.replay()) fresh++;}
            assertThat(ids).hasSize(1); assertThat(fresh).isEqualTo(1);
        }
    }
    @Test void expiredLeaseFencesOldWorkerAndSupportsRetriesAcrossMonths() {
        var old=acceptClaim(); assertThat(old.origin()).isEqualTo("rgm"); assertThat(queue.renew(old,minute)).isTrue();
        jdbc.update("UPDATE collector_job SET locked_until=clock_timestamp()-interval '1 second'");
        assertThat(queue.renew(old,minute)).isFalse();
        assertThat(queue.complete(old,"FAILED",UUID.randomUUID(),UUID.randomUUID(),"{}")).isFalse();
        var current=queue.claim("b",minute).orElseThrow(); assertThat(current.generation()).isEqualTo(old.generation()+1);
        assertThat(queue.retry(old,Instant.now())).isFalse();
        assertThat(jdbc.queryForObject("SELECT state FROM collector_execution WHERE generation=1",String.class)).isEqualTo("LEASE_EXPIRED");
        assertThat(queue.retry(current,Instant.parse("2099-01-01T00:00:00Z"))).isTrue();
        assertThat(queue.claim("c",minute)).isEmpty(); assertThat(queue.archive(current.id(),current.generation())).isFalse();
        jdbc.update("UPDATE collector_job SET next_attempt_at=clock_timestamp()-interval '1 second'");
        finish(queue.claim("c",minute).orElseThrow());
    }
    @Test void deliveryLeaseRecoveryAndRetryAreFenced() {
        finish(acceptClaim()); var old=queue.claimDelivery("a",minute).orElseThrow();
        jdbc.update("UPDATE collector_delivery SET locked_until=clock_timestamp()-interval '1 second'");
        assertThat(queue.acknowledge(old)).isFalse();
        var d=queue.claimDelivery("b",minute).orElseThrow(); assertThat(d.payload()).contains("42");
        assertThat(queue.retryDelivery(old,Instant.now())).isFalse();
        assertThat(queue.retryDelivery(d,Instant.parse("2099-01-01T00:00:00Z"))).isTrue();
        assertThat(queue.claimDelivery("c",minute)).isEmpty();
        jdbc.update("UPDATE collector_delivery SET next_attempt_at=clock_timestamp()-interval '1 second'");
        assertThat(queue.acknowledge(queue.claimDelivery("c",minute).orElseThrow())).isTrue();
        assertThat(queue.claimDelivery("d",minute)).isEmpty();
    }
    @Test void archiveFailureRollsBackAllHotRows() {
        var l=acceptClaim(); finish(l); queue.acknowledge(queue.claimDelivery("a",minute).orElseThrow());
        jdbc.execute("CREATE FUNCTION reject_archive() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test crash'; END $$");
        jdbc.execute("CREATE TRIGGER archive_crash BEFORE DELETE ON collector_job FOR EACH ROW EXECUTE FUNCTION reject_archive()");
        try {
            assertThatThrownBy(()->queue.archive(l.id(),l.generation())).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM collector_job_history",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT archived_at FROM collector_job_identity",Timestamp.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM collector_delivery",Long.class)).isEqualTo(1);
        } finally { jdbc.execute("DROP TRIGGER archive_crash ON collector_job"); jdbc.execute("DROP FUNCTION reject_archive()"); }
        assertThat(queue.archive(l.id(),l.generation())).isTrue();
    }
    @Test void monthlyBoundsDetachAndPrunedLookups() {
        var s=submission(); var a=queue.accept(s);
        partitions.ensure(YearMonth.of(2020,2));
        jdbc.update("INSERT INTO collector_job_history VALUES ('2020-02-29 23:59:59+00',?,?::jsonb)",a.id(),"{}");
        jdbc.update("UPDATE collector_job_identity SET archived_at='2020-02-29 23:59:59+00' WHERE id=?",a.id());
        jdbc.update("DELETE FROM collector_job WHERE id=?",a.id());
        assertThat(queue.find(a.publicId()).orElseThrow().snapshot()).isEqualTo("{}");
        assertThatThrownBy(()->partitions.detach(YearMonth.of(2020,2),Instant.parse("2020-02-15T00:00:00Z"))).isInstanceOf(IllegalArgumentException.class);
        partitions.detach(YearMonth.of(2020,2),Instant.parse("2020-03-01T00:00:00Z"));
        assertThat(queue.find(a.publicId()).orElseThrow().snapshot()).isNull(); assertThat(queue.accept(s).replay()).isTrue();
        jdbc.execute("DROP TABLE history_2020_02");
        String bounds=jdbc.queryForObject("SELECT pg_get_expr(relpartbound,oid) FROM pg_class WHERE relname='history_2027_02'",String.class);
        assertThat(bounds).contains("2027-02-01","2027-03-01");
        assertThat(jdbc.queryForList("EXPLAIN "+PostgresJobQueue.CLAIM_SQL,String.class).toString()).doesNotContain("history");
        assertThat(queue.find(UUID.randomUUID())).isEmpty();
    }
    @Test void concurrentArchiveAndClaimsKeepUnfinishedJobsActive() throws Exception {
        var accepted=queue.accept(submission()); var lease=queue.claim("a",minute).orElseThrow(); finish(lease);
        queue.acknowledge(queue.claimDelivery("sender",minute).orElseThrow());
        for(int i=0;i<10;i++) queue.accept(submission());
        try(var pool=Executors.newFixedThreadPool(4)) {
            var archive=pool.submit(()->queue.archive(lease.id(),lease.generation()));
            var duplicate=pool.submit(()->queue.archive(lease.id(),lease.generation()));
            var read=pool.submit(()-> {for(int i=0;i<15;i++) assertThat(queue.find(accepted.publicId()).orElseThrow().snapshot()).isNotNull();});
            var claim=pool.submit(()-> {for(int i=0;i<10;i++) assertThat(queue.claim("worker",minute).orElseThrow().id()).isNotEqualTo(lease.id());});
            assertThat(List.of(archive.get(),duplicate.get())).containsExactlyInAnyOrder(true,false); read.get(); claim.get();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collector_job",Long.class)).isEqualTo(10);
    }
    @Test void lockedCandidateDoesNotBlockOtherClaims() throws Exception {
        queue.accept(submission()); queue.accept(submission());
        var ready=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var locked=pool.submit(()->new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{
                jdbc.queryForObject("SELECT id FROM collector_job ORDER BY id LIMIT 1 FOR UPDATE",Long.class);
                ready.countDown(); try {if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Timeout");} catch(InterruptedException e) {throw new IllegalStateException(e);} return true;
            }));
            try {assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); assertThat(queue.claim("other",minute).orElseThrow().id()).isEqualTo(2);}
            finally {release.countDown();} assertThat(locked.get()).isTrue();
        }
    }
    @Test void storageBeansDoNotReplaceProductDatasource() {
        jdbc.execute("CREATE DATABASE product");
        String product=pg.getJdbcUrl().replace("/test?","/product?");
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,LiquibaseAutoConfiguration.class))
            .withUserConfiguration(CollectorStorageConfiguration.class)
            .withPropertyValues("collector.storage.enabled=true","collector.datasource.url="+pg.getJdbcUrl(),
                "collector.datasource.username="+pg.getUsername(),"collector.datasource.password="+pg.getPassword(),
                "spring.liquibase.change-log=classpath:product-test.yaml","spring.datasource.url="+product,"spring.datasource.username="+pg.getUsername(),"spring.datasource.password="+pg.getPassword())
            .run(context->{
                assertThat(context).hasNotFailed();
                DataSource collector=context.getBean("collectorDataSource",DataSource.class);
                DataSource rgm=context.getBean("dataSource",DataSource.class);
                assertThat(collector).isNotSameAs(rgm);
                assertThat(context.getBean("liquibase",SpringLiquibase.class).getDataSource()).isSameAs(rgm);
                assertThat(new JdbcTemplate(rgm).queryForObject("SELECT count(*) FROM product_history",Long.class)).isEqualTo(1);
                assertThat(new JdbcTemplate(rgm).queryForObject("SELECT current_database()",String.class)).isEqualTo("product");
                assertThat(new JdbcTemplate(rgm).queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name='collector_job'",Long.class)).isZero();
            });
    }

    @Test void lostCallbackAckRetriesIdenticalResultWithoutCollectingAgain()throws Exception{
        var received=new java.util.ArrayList<String>();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/callback",exchange->{
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer callback-service-secret-0123456789012345");
            received.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));exchange.sendResponseHeaders(calls.incrementAndGet()==1?503:204,-1);exchange.close();
        });server.start();
        try{
            var endpoint=java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort());
            var credentials=new com.kholodilin.collector.credentials.CollectorCredentialProperties(true,null,0,0,null,null,null,true,List.of(new com.kholodilin.collector.credentials.CollectorCredentialProperties.Origin("rgm",endpoint,"callback-service-secret-0123456789012345")));
            var properties=new com.kholodilin.collector.delivery.DeliveryProperties(true,null,Duration.ofMillis(1),null,3,true,List.of(new com.kholodilin.collector.delivery.DeliveryProperties.Destination("rgm-results","rgm",endpoint.resolve("/callback"))));
            var sender=new com.kholodilin.collector.delivery.ResultDeliveryWorker(queue,properties,credentials);
            var lease=acceptClaim();UUID event=UUID.randomUUID();String result="{\"eventId\":\""+event+"\",\"data\":42}";assertThat(queue.complete(lease,"SUCCEEDED",UUID.randomUUID(),event,result)).isTrue();
            assertThat(sender.poll("sender-a")).isTrue();assertThat(jdbc.queryForObject("SELECT state FROM collector_delivery",String.class)).isEqualTo("RETRY");
            jdbc.update("UPDATE collector_delivery SET next_attempt_at=clock_timestamp()-interval '1 second'");
            // Reconstruct sender/queue objects, retaining only database state after a process restart.
            var restarted=new PostgresJobQueue(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),partitions);
            assertThat(new com.kholodilin.collector.delivery.ResultDeliveryWorker(restarted,properties,credentials).poll("sender-b")).isTrue();
            assertThat(received).hasSize(2);assertThat(received.get(0)).isEqualTo(received.get(1));assertThat(toJson(received.getFirst()).path("eventId").asText()).isEqualTo(event.toString());
            assertThat(queue.claim("collector",minute)).isEmpty();assertThat(jdbc.queryForObject("SELECT count(*) FROM collector_job",Long.class)).isZero();
            assertThat(queue.result(lease.publicId(),"rgm")).isPresent();assertThat(queue.result(lease.publicId(),"another-origin")).isEmpty();
            assertThat(queue.status(lease.publicId(),"rgm").orElseThrow().status().name()).isEqualTo("SUCCEEDED");
        }finally{server.stop(0);}
    }
    @Test void deadDeliveryCanBeRecoveredByRecipientAndOldSenderIsFenced(){
        var s=submission();queue.accept(s);var lease=queue.claim("a",minute).orElseThrow();UUID event=UUID.randomUUID();queue.complete(lease,"SUCCEEDED",UUID.randomUUID(),event,"{}");
        var sender=queue.claimDelivery("sender-a",minute).orElseThrow();assertThat(queue.deadLetter(sender,"REJECTED")).isTrue();
        assertThat(queue.diagnostics("rgm")).containsEntry("deadDeliveries",1L);assertThat(queue.requeueDelivery(lease.publicId(),"other")).isFalse();
        assertThat(queue.requeueDelivery(lease.publicId(),"rgm")).isTrue();var newer=queue.claimDelivery("sender-b",minute).orElseThrow();assertThat(queue.acknowledge(sender)).isFalse();
        assertThat(queue.confirmDelivery(lease.publicId(),"rgm",UUID.randomUUID())).isFalse();assertThat(queue.confirmDelivery(lease.publicId(),"rgm",event)).isTrue();assertThat(queue.retryDelivery(newer,Instant.now())).isFalse();
        assertThat(queue.archive(lease.id(),lease.generation())).isTrue();assertThat(queue.statusByAttempt(s.attemptId(),"rgm")).isPresent();
    }
    @Test void hundredRepositoriesStayFairAcrossTaskTypesAndQuotaWaits(){
        long start=System.nanoTime();for(int project=0;project<100;project++)for(String type:List.of("STATS","TRAFFIC","REFERRERS","POPULAR_PATHS","GROWTH_EVENTS","SEARCH_QUERY","TOPIC_WATCH","TOPIC_WATCH")){
            var base=submission();queue.accept(new Submission("rgm",base.jobId(),base.attemptId(),"{\"type\":\""+type+"\",\"project\":"+project+"}",base.destination(),base.deadline()));
        }
        assertThat(queue.diagnostics("rgm")).containsEntry("pending",800L);Set<String> types=new HashSet<>();
        for(int n=0;n<8;n++){var lease=queue.claim("load",minute).orElseThrow();types.add(toJson(lease.payload()).path("type").asText());queue.retry(lease,Instant.now().plusSeconds(600),false);}
        assertThat(types).contains("SEARCH_QUERY","TOPIC_WATCH","STATS");var next=queue.claim("load",minute).orElseThrow();assertThat(toJson(next.payload()).path("project").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sum(failure_count) FROM collector_job",Long.class)).isZero();
        System.out.printf("WORKLOAD 100 repositories / 800 jobs: insert=%dms, oldestQueueAgeSeconds=%s, JDBC connections per claim=1%n",(System.nanoTime()-start)/1_000_000,queue.diagnostics("rgm").get("oldestQueueAgeSeconds"));
    }
    private tools.jackson.databind.JsonNode toJson(String value){return tools.jackson.databind.json.JsonMapper.builder().build().readTree(value);}

    @Test void failoverQueuesUseIndependentDatabasesAndKeepOneStableExternalJob()throws Exception{
        jdbc.execute("CREATE DATABASE collector_b");
        try{
            String url="jdbc:postgresql://"+pg.getHost()+":"+pg.getMappedPort(5432)+"/collector_b";
            var bds=new DriverManagerDataSource(url,pg.getUsername(),pg.getPassword());var bj=new JdbcTemplate(bds);var migration=new SpringLiquibase();migration.setDataSource(bds);migration.setChangeLog("classpath:collector/db/changelog/master.yaml");migration.afterPropertiesSet();
            var bp=new HistoryPartitions(bj);bp.ensureCurrentAndNext();var b=new PostgresJobQueue(bj,new TransactionTemplate(new DataSourceTransactionManager(bds)),bp);
            var a=submission();var original=queue.accept(a);var old=queue.claim("node-a",minute).orElseThrow();
            var replacement=new Submission(a.origin(),a.jobId(),UUID.randomUUID(),a.payload(),a.destination(),a.deadline());var accepted=b.accept(replacement);var current=b.claim("node-b",minute).orElseThrow();
            assertThat(accepted.publicId()).isNotEqualTo(original.publicId());assertThat(b.statusByAttempt(a.attemptId(),"rgm")).isEmpty();assertThat(queue.statusByAttempt(replacement.attemptId(),"rgm")).isEmpty();
            assertThat(b.complete(current,"SUCCEEDED",UUID.randomUUID(),UUID.randomUUID(),"{\"node\":\"b\"}")).isTrue();
            jdbc.update("UPDATE collector_job SET locked_until=clock_timestamp()-interval '1 second'");assertThat(queue.complete(old,"SUCCEEDED",UUID.randomUUID(),UUID.randomUUID(),"{}")).isFalse();
            assertThat(b.statusByAttempt(replacement.attemptId(),"rgm").orElseThrow().jobId()).isEqualTo(a.jobId());
            assertThat(bj.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name='repository'",Long.class)).isZero();
        }finally{jdbc.execute("DROP DATABASE collector_b WITH (FORCE)");}
    }

    @Test void crashAfterAcknowledgmentDoesNotStrandDeliveredRowsInHotQueue(){
        var lease=acceptClaim();finish(lease);assertThat(queue.acknowledge(queue.claimDelivery("sender",minute).orElseThrow())).isTrue();
        var restarted=new PostgresJobQueue(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),partitions);
        assertThat(restarted.archiveDelivered()).isTrue();assertThat(restarted.archiveDelivered()).isFalse();assertThat(restarted.result(lease.publicId(),"rgm")).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collector_job",Long.class)).isZero();
    }
}
