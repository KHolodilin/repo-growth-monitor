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
        var old=acceptClaim(); assertThat(queue.renew(old,minute)).isTrue();
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
}
