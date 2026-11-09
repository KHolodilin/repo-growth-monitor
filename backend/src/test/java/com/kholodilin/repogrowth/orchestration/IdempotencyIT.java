package com.kholodilin.repogrowth.orchestration;

import com.kholodilin.collector.acceptance.CollectorAcceptanceService;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.queue.JobQueue;
import com.kholodilin.idempotency.*;
import com.kholodilin.idempotency.exception.MissingTransactionException;
import com.kholodilin.idempotency.jdbc.JdbcIdempotencyPersistenceCleanup;
import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.mock.MockUserCatalog;
import com.kholodilin.repogrowth.auth.security.*;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"rgm.orchestration.enabled=true","collector.storage.enabled=true","collector.http.enabled=true",
    "collector.http.clients[0].origin=rgm-a","collector.http.clients[0].token="+IdempotencyIT.TOKEN_A,
    "collector.http.clients[1].origin=rgm-b","collector.http.clients[1].token="+IdempotencyIT.TOKEN_B,
    "rgm.orchestration.nodes[0].key=collector","rgm.orchestration.nodes[0].pool=default",
    "rgm.orchestration.nodes[0].endpoint=https://collector.example","rgm.orchestration.nodes[0].enabled=true"})
@AutoConfigureMockMvc
class IdempotencyIT extends AbstractPostgresTest {
    static final String TOKEN_A="test-service-a-01234567890123456789", TOKEN_B="test-service-b-01234567890123456789";
    static PostgreSQLContainer collector=new PostgreSQLContainer("postgres:18.6");
    static {collector.start();}
    @DynamicPropertySource static void collectorProperties(DynamicPropertyRegistry registry) {
        registry.add("collector.datasource.url",collector::getJdbcUrl);
        registry.add("collector.datasource.username",collector::getUsername);
        registry.add("collector.datasource.password",collector::getPassword);
    }
    @AfterAll static void stopCollector() {collector.stop();}
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcClient jdbcClient;
    @Autowired UserAccountService users;
    @Autowired MockUserCatalog catalog;
    @Autowired JsonMapper json;
    @Autowired IdempotentCollectionRequests requests;
    @Autowired CollectorAcceptanceService acceptance;
    @Autowired JobQueue queue;
    @Autowired @Qualifier("rgmIdempotency") IdempotencyService rgmIdempotency;
    @Autowired @Qualifier("collectorIdempotency") IdempotencyService collectorIdempotency;
    @Autowired @Qualifier("collectorTransactionManager") PlatformTransactionManager collectorTm;
    @Autowired PlatformTransactionManager tm;
    @Autowired @Qualifier("collectorDataSource") DataSource collectorDs;
    @Autowired DataSource productDs;
    JdbcTemplate cj;
    long repositoryId,otherId,adminId;
    @BeforeEach void reset() {
        cj=new JdbcTemplate(collectorDs);
        cj.execute("TRUNCATE collector_observation,idempotency_records,collector_job_identity,collector_job,collector_execution,collector_result,collector_delivery,collector_job_history RESTART IDENTITY");
        jdbc.execute("TRUNCATE collection_history_mapping,collector_snapshot_header,idempotency_records,collection_request_command,collector_result_receipt,collector_dispatch,collection_attempt,collection_task,collection_request,collector_node RESTART IDENTITY");
        wipeRepositoryData(jdbcClient); catalog.syncMissingUsers(); adminId=users.findByIssuerAndSub(MockUserCatalog.ISSUER,"admin").orElseThrow().id();
        long owner=jdbc.queryForObject("INSERT INTO github_owner(github_id,login,owner_type) VALUES (101,'KHolodilin','USER') RETURNING id",Long.class);
        repositoryId=insertRepository(202,owner,"repo-growth-monitor");otherId=insertRepository(303,owner,"other");
    }
    long insertRepository(long githubId,long owner,String name) {
        return jdbc.queryForObject("INSERT INTO repository(github_id,owner_id,name,full_name,visibility,tracking_enabled) VALUES (?,?,?,?,'PUBLIC',true) RETURNING id",Long.class,githubId,owner,name,"KHolodilin/"+name);
    }
    JobRequest job() {return new JobRequest(1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),TaskType.STATS,new RepositoryTarget("202","KHolodilin","repo-growth-monitor"),LocalDate.now(),1,Parameters.repository(),"rgm-results",Instant.parse("2027-01-01T00:00:00Z"));}
    String manualUrl(long id) {return "/api/v1/admin/repositories/"+id+"/collections";}
    RequestPostProcessor user(String sub) {
        var user=users.findByIssuerAndSub(MockUserCatalog.ISSUER,sub).orElseThrow();
        return authentication(UsernamePasswordAuthenticationToken.authenticated(new AppUserPrincipal(user.id(),user.displayName()),null,SecurityAuthorities.from(user)));
    }
    String submit(JobRequest job,String token,int status) throws Exception {
        return mvc.perform(post("/internal/v1/jobs").header("Authorization","Bearer "+token).header("Idempotency-Key",job.attemptId().toString()).contentType("application/json").content(json.writeValueAsString(job)))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    }
    @Test void collectorReplaysStableAcceptedIdsAndScopesByAuthenticatedOrigin() throws Exception {
        var job=job();String first=submit(job,TOKEN_A,201);assertThat(submit(job,TOKEN_A,201)).isEqualTo(first);
        assertThat(json.readTree(submit(job,TOKEN_B,201)).get("collectorJobId").asText()).isNotEqualTo(json.readTree(first).get("collectorJobId").asText());
        assertThat(cj.queryForObject("SELECT count(*) FROM collector_job",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records",Long.class)).isZero();
        assertThat(cj.queryForList("SELECT DISTINCT operation FROM idempotency_records",String.class)).containsExactly("ACCEPT_COLLECTOR_JOB");
    }
    @Test void changedCollectorPayloadConflictsBeforeAndAfterRetentionCleanup() throws Exception {
        var job=job();submit(job,TOKEN_A,201);
        var changed=new JobRequest(1,job.requestId(),job.jobId(),job.attemptId(),job.type(),new RepositoryTarget("202","KHolodilin","renamed"),job.businessDate(),2,job.parameters(),job.destinationRef(),job.deadlineAt());
        submit(changed,TOKEN_A,409);
        var otherAttempt=new JobRequest(1,job.requestId(),job.jobId(),UUID.randomUUID(),job.type(),job.repository(),job.businessDate(),1,job.parameters(),job.destinationRef(),job.deadlineAt());
        mvc.perform(post("/internal/v1/jobs").header("Authorization","Bearer "+TOKEN_A).header("Idempotency-Key",job.attemptId().toString()).contentType("application/json").content(json.writeValueAsString(otherAttempt))).andExpect(status().isConflict());
        expire(cj,collectorDs);submit(changed,TOKEN_A,409);
        assertThat(cj.queryForObject("SELECT count(*) FROM collector_job",Long.class)).isEqualTo(1);
    }
    @Test void replayAfterArchiveAndCleanupCannotStartAnotherExecution() throws Exception {
        var job=job();String accepted=submit(job,TOKEN_A,201);var lease=queue.claim("worker",Duration.ofMinutes(1)).orElseThrow();
        queue.complete(lease,"SUCCEEDED",UUID.randomUUID(),UUID.randomUUID(),"{}");queue.acknowledge(queue.claimDelivery("sender",Duration.ofMinutes(1)).orElseThrow());
        assertThat(queue.archive(lease.id(),lease.generation())).isTrue();expire(cj,collectorDs);
        assertThat(submit(job,TOKEN_A,201)).isEqualTo(accepted);assertThat(queue.claim("worker",Duration.ofMinutes(1))).isEmpty();
    }
    @Test void authorizationAndValidationHappenBeforeCollectorReplay() throws Exception {
        var job=job();submit(job,TOKEN_A,201);
        submit(job,"revoked-token",401);
        mvc.perform(post("/internal/v1/jobs").contentType("application/json").content("not-json")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/jobs").with(user("admin")).with(csrf()).contentType("application/json").content(json.writeValueAsString(job))).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/jobs").header("Authorization","Bearer "+TOKEN_A).header("Idempotency-Key","wrong").contentType("application/json").content(json.writeValueAsString(job))).andExpect(status().isBadRequest());
        assertThat(cj.queryForObject("SELECT count(*) FROM collector_job",Long.class)).isEqualTo(1);
    }
    @Test void concurrentDuplicatesUseTheStarterInBothStores() throws Exception {
        var job=job();
        try(var pool=Executors.newFixedThreadPool(5)) {
            List<Callable<JobAccepted>> collectorTasks=new ArrayList<>();for(int i=0;i<10;i++) collectorTasks.add(()->acceptance.accept("rgm-a",job.attemptId().toString(),job));
            Set<UUID> ids=new HashSet<>();for(var f:pool.invokeAll(collectorTasks)) ids.add(f.get().collectorJobId());assertThat(ids).hasSize(1);
            List<Callable<CollectionRequestService.Created>> productTasks=new ArrayList<>();for(int i=0;i<10;i++) productTasks.add(()->requests.create(adminId,"manual-key",repositoryId,LocalDate.now()));
            Set<UUID> requestIds=new HashSet<>();for(var f:pool.invokeAll(productTasks)) requestIds.add(f.get().requestId());assertThat(requestIds).hasSize(1);
        }
        assertThat(cj.queryForObject("SELECT count(*) FROM idempotency_records",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collection_task",Long.class)).isEqualTo(5);
    }
    @Test void productReplaySurvivesCompletionCleanupAndCoalescedAliases() throws Exception {
        String a=mvc.perform(post(manualUrl(repositoryId)).with(user("admin")).with(csrf()).header("Idempotency-Key","a")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String b=mvc.perform(post(manualUrl(repositoryId)).with(user("admin")).with(csrf()).header("Idempotency-Key","b")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(a).get("requestId")).isEqualTo(json.readTree(b).get("requestId"));
        jdbc.update("UPDATE collection_request SET state='SUCCEEDED'");expire(jdbc,productDs);
        for(var entry:Map.of("a",a,"b",b).entrySet()) {
            String replay=mvc.perform(post(manualUrl(repositoryId)).with(user("admin")).with(csrf()).header("Idempotency-Key",entry.getKey())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            assertThat(replay).isEqualTo(entry.getValue());
        }
        mvc.perform(post(manualUrl(otherId)).with(user("admin")).with(csrf()).header("Idempotency-Key","a")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collection_request",Long.class)).isEqualTo(1);
    }
    @Test void productConflictsAndRevokedAuthorityCannotReplay() throws Exception {
        requests.create(adminId,"key",repositoryId,LocalDate.now());
        mvc.perform(post(manualUrl(otherId)).with(user("admin")).with(csrf()).header("Idempotency-Key","key")).andExpect(status().isConflict());
        mvc.perform(post(manualUrl(repositoryId)).with(user("admin")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post(manualUrl(repositoryId)).with(user("admin")).with(csrf()).header("Idempotency-Key","invalid key")).andExpect(status().isBadRequest());
        users.revokeAdmin(adminId);
        try {
            mvc.perform(post(manualUrl(repositoryId)).with(user("admin")).with(csrf()).header("Idempotency-Key","key")).andExpect(status().isForbidden());
            assertThatThrownBy(()->requests.create(adminId,"key",repositoryId,LocalDate.now())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally {users.grantAdmin(adminId);}
    }
    @Test void productActorScopeDoesNotCollide() {
        var other=users.findByIssuerAndSub(MockUserCatalog.ISSUER,"user").orElseThrow();users.grantAdmin(other.id());
        try {
            var a=requests.create(adminId,"same-key",repositoryId,LocalDate.now());var b=requests.create(other.id(),"same-key",otherId,LocalDate.now());
            assertThat(a.requestId()).isNotEqualTo(b.requestId());assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records",Long.class)).isEqualTo(2);
        } finally {users.revokeAdmin(other.id());}
    }
    @Test void owningDatasourceIsRequiredEvenWhenSomeTransactionAndAnotherBoundConnectionExist() {
        assertThatThrownBy(()->new TransactionTemplate(tm).execute(s->{
            cj.queryForObject("SELECT 1",Integer.class); // Connection can be bound to synchronization without owning a transaction.
            return collectorIdempotency.operation("TEST").key("same").request("x").execute(String.class,()->ExecutionResult.success("bad"));
        })).isInstanceOf(MissingTransactionException.class);
        assertThatThrownBy(()->new TransactionTemplate(collectorTm).execute(s->rgmIdempotency.operation("TEST").key("same").request("x").execute(String.class,()->ExecutionResult.success("bad")))).isInstanceOf(MissingTransactionException.class);
        new TransactionTemplate(tm).execute(s->rgmIdempotency.operation("TEST").key("same").request("x").execute(String.class,()->ExecutionResult.success("product")));
        new TransactionTemplate(collectorTm).execute(s->collectorIdempotency.operation("TEST").key("same").request("x").execute(String.class,()->ExecutionResult.success("collector")));
        assertThat(jdbc.queryForObject("SELECT result_payload::text FROM idempotency_records",String.class)).contains("product");
        assertThat(cj.queryForObject("SELECT result_payload::text FROM idempotency_records",String.class)).contains("collector");
    }
    @Test void productActionFailureRollsBackReplayRecordAndAllBusinessWrites() {
        jdbc.update("INSERT INTO search_query(repository_id,name,query,enabled,result_limit) VALUES (?,'bad','stars:>10',true,101)",repositoryId);
        assertThatThrownBy(()->requests.create(adminId,"rollback",repositoryId,LocalDate.now())).isInstanceOf(IllegalArgumentException.class);
        for(String table:List.of("idempotency_records","collection_request_command","collection_request","collection_task","collector_dispatch")) assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class)).isZero();
        jdbc.update("UPDATE search_query SET result_limit=50");assertThat(requests.create(adminId,"rollback",repositoryId,LocalDate.now()).taskIds()).hasSize(6);
    }
    @Test void collectorActionFailureRollsBackAcceptanceAndCanRetry() throws Exception {
        var job=job();
        cj.execute("CREATE FUNCTION reject_acceptance() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test crash'; END $$");
        cj.execute("CREATE TRIGGER acceptance_crash BEFORE INSERT ON collector_job FOR EACH ROW EXECUTE FUNCTION reject_acceptance()");
        try {submit(job,TOKEN_A,503);assertThat(cj.queryForObject("SELECT count(*) FROM idempotency_records",Long.class)).isZero();assertThat(cj.queryForObject("SELECT count(*) FROM collector_job_identity",Long.class)).isZero();}
        finally {cj.execute("DROP TRIGGER acceptance_crash ON collector_job");cj.execute("DROP FUNCTION reject_acceptance()");}
        submit(job,TOKEN_A,201);
    }
    void expire(JdbcTemplate jdbc,DataSource ds) {
        jdbc.update("UPDATE idempotency_records SET expires_at=clock_timestamp()-interval '1 second'");
        assertThat(new JdbcIdempotencyPersistenceCleanup(ds).deleteExpired(Instant.now(),100)).isPositive();
    }
}
