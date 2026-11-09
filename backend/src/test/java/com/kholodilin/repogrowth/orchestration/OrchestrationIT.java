package com.kholodilin.repogrowth.orchestration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.mock.MockUserCatalog;
import com.kholodilin.repogrowth.auth.security.*;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import com.kholodilin.repogrowth.collection.planner.*;
import com.kholodilin.repogrowth.collection.worker.CollectionWorkerPool;
import com.kholodilin.repogrowth.search.worker.SearchWorkerPool;
import com.kholodilin.repogrowth.search.planner.SearchPlanner;
import com.kholodilin.repogrowth.topic.planner.TopicPlanner;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"rgm.orchestration.enabled=true","rgm.orchestration.allow-http=true","rgm.orchestration.retry-delay=1ms"})
@AutoConfigureMockMvc
class OrchestrationIT extends AbstractPostgresTest {
    static WireMockServer server=new WireMockServer(wireMockConfig().dynamicPort());
    static {server.start();}
    @DynamicPropertySource static void nodes(DynamicPropertyRegistry registry) {
        registry.add("rgm.orchestration.nodes[0].key",()->"node-a");
        registry.add("rgm.orchestration.nodes[0].pool",()->"default");
        registry.add("rgm.orchestration.nodes[0].endpoint",server::baseUrl);
        registry.add("rgm.orchestration.nodes[0].enabled",()->"true");
        registry.add("rgm.orchestration.nodes[0].service-token",()->"service-test-secret");
        registry.add("rgm.orchestration.nodes[1].key",()->"node-b");
        registry.add("rgm.orchestration.nodes[1].pool",()->"default");
        registry.add("rgm.orchestration.nodes[1].endpoint",()->server.baseUrl()+"/b");
        registry.add("rgm.orchestration.nodes[1].enabled",()->"true");
        registry.add("rgm.orchestration.nodes[1].service-token",()->"node-b-service-token-012345678901234");
    }
    @AfterAll static void stopServer() {server.stop();}
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcClient jdbcClient;
    @Autowired CollectionRequestService service;
    @Autowired DispatchRepository dispatch;
    @Autowired CollectorDispatcher dispatcher;
    @Autowired CollectorTransport transport;
    @Autowired JsonMapper json;
    @Autowired MockMvc mvc;
    @Autowired UserAccountService users;
    @Autowired MockUserCatalog catalog;
    @Autowired ApplicationContext context;
    @Autowired DailyPlanner daily;
    @Autowired CollectionPlanner legacy;
    @Autowired SearchPlanner legacySearch;
    @Autowired TopicPlanner legacyTopic;
    @Autowired PlatformTransactionManager tm;
    long repositoryId;
    LocalDate day=LocalDate.of(2026,11,8);
    @BeforeEach void seed() {
        server.resetAll();
        jdbc.execute("TRUNCATE collection_history_mapping,collector_snapshot_header,idempotency_records,collection_request_command,collector_result_receipt,collector_dispatch,collection_attempt,collection_task,collection_request,collector_node RESTART IDENTITY");
        wipeRepositoryData(jdbcClient);
        long owner=jdbc.queryForObject("INSERT INTO github_owner(github_id,login,owner_type) VALUES (101,'KHolodilin','USER') RETURNING id",Long.class);
        repositoryId=jdbc.queryForObject("INSERT INTO repository(github_id,owner_id,name,full_name,visibility,tracking_enabled,language) VALUES (202,?,'repo-growth-monitor','KHolodilin/repo-growth-monitor','PUBLIC',true,'Java') RETURNING id",Long.class,owner);
        jdbc.update("INSERT INTO repository_topics VALUES (?,'spring')",repositoryId);
        jdbc.update("INSERT INTO search_query(repository_id,name,query,enabled,result_limit) VALUES (?,'primary','spring stars:>10',true,50),(?,'disabled','unused',false,50)",repositoryId,repositoryId);
        jdbc.update("INSERT INTO collection_run(repository_id,business_date,status,planned_jobs) VALUES (?,'2020-01-01','SUCCESS',5)",repositoryId);
        catalog.syncMissingUsers();
    }
    @Test void invocationExpandsAndFreezesEveryConcreteTarget() {
        var created=service.manual(repositoryId,day);
        assertThat(created.taskIds()).hasSize(8); // five repository, one query, two topic/language watches
        assertThat(count("collection_attempt")).isEqualTo(8); assertThat(count("collector_dispatch")).isEqualTo(8);
        var first=dispatch.claim("sender").orElseThrow();
        assertThat(first.request().repository()).isEqualTo(new RepositoryTarget("202","KHolodilin","repo-growth-monitor"));
        assertThat(first.request().requestId()).isEqualTo(created.requestId());
        String before=jdbc.queryForObject("SELECT configuration_snapshot::text FROM collection_task WHERE task_type='SEARCH_QUERY'",String.class);
        jdbc.update("UPDATE search_query SET query='changed' WHERE enabled");
        assertThat(service.manual(repositoryId,day).requestId()).isEqualTo(created.requestId());
        assertThat(jdbc.queryForObject("SELECT configuration_snapshot::text FROM collection_task WHERE task_type='SEARCH_QUERY'",String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM collection_run",String.class)).isEqualTo("SUCCESS");
        assertThat(count("collection_run")).isEqualTo(1); assertThat(count("collection_job")).isZero();
        assertThat(count("search_run")).isZero(); assertThat(count("topic_run")).isZero();
    }
    @Test void manualAndScheduleCoalesceConcurrentlyAndScheduledKeySurvivesCompletion() throws Exception {
        try(var pool=Executors.newFixedThreadPool(6)) {
            List<Callable<UUID>> work=new ArrayList<>();
            for(int i=0;i<12;i++) work.add(()->service.manual(repositoryId,day).requestId());
            Set<UUID> ids=new HashSet<>(); for(var future:pool.invokeAll(work)) ids.add(future.get());
            assertThat(ids).hasSize(1); assertThat(count("collection_request")).isEqualTo(1);
        }
        service.scheduled(day,"09:00"); assertThat(count("collection_request")).isEqualTo(1);
        jdbc.update("UPDATE collection_request SET state='SUCCEEDED'");
        service.scheduled(day,"09:00"); assertThat(count("collection_request")).isEqualTo(2);
        jdbc.update("UPDATE collection_request SET state='SUCCEEDED'");
        service.scheduled(day,"09:00"); assertThat(count("collection_request")).isEqualTo(2);
        assertThat(service.manual(repositoryId,day).coalesced()).isFalse(); assertThat(count("collection_request")).isEqualTo(3);
    }
    @Test void creationFailureRollsBackInvocationTasksNodesAndOutbox() {
        jdbc.update("UPDATE search_query SET result_limit=101 WHERE enabled");
        assertThatThrownBy(()->service.manual(repositoryId,day)).isInstanceOf(IllegalArgumentException.class);
        for(String table:List.of("collection_request","collection_task","collection_attempt","collector_dispatch","collector_node")) assertThat(count(table)).isZero();
        assertThat(count("collection_run")).isEqualTo(1);
        assertThatThrownBy(()->service.manual(-1,day)).hasMessageContaining("Repository not found");
    }
    @Test void lostAcceptanceIsResentWithIdenticalAttemptAndPayload() {
        service.manual(repositoryId,day); var original=dispatch.claim("crashed-sender").orElseThrow();
        accept(original.request()); transport.send(original); // Response received, sender crashes before durable ACK.
        jdbc.update("UPDATE collector_dispatch SET locked_until=clock_timestamp()-interval '1 second' WHERE id=?",original.id());
        // Other pending tasks should not obscure expired assignment in this test.
        jdbc.update("UPDATE collector_dispatch SET next_attempt_at=clock_timestamp()+interval '1 day' WHERE id<>?",original.id());
        assertThat(dispatcher.dispatchOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT state FROM collector_dispatch WHERE id=?",String.class,original.id())).isEqualTo("ACKED");
        server.verify(2,postRequestedFor(urlEqualTo("/internal/v1/jobs")).withHeader("Idempotency-Key",equalTo(original.request().attemptId().toString())).withHeader("Authorization",equalTo("Bearer service-test-secret")));
        var requests=server.findAll(postRequestedFor(urlEqualTo("/internal/v1/jobs")));
        assertThat(requests.get(0).getBodyAsString()).isEqualTo(requests.get(1).getBodyAsString());
        assertThat(dispatch.acknowledge(original,new JobAccepted(1,UUID.randomUUID(),original.request().jobId(),original.request().attemptId(),JobState.ACCEPTED,Instant.now()))).isFalse();
    }
    @Test void callbackBeforeAckDoesNotDowngradeCompletedState() {
        service.manual(repositoryId,day); var claim=dispatch.claim("sender").orElseThrow();
        jdbc.update("UPDATE collection_attempt SET state='SUCCEEDED',completed_at=clock_timestamp() WHERE public_id=?",claim.request().attemptId());
        jdbc.update("UPDATE collection_task SET state='SUCCEEDED',completed_at=clock_timestamp() WHERE public_id=?",claim.request().jobId());
        var accepted=new JobAccepted(1,UUID.randomUUID(),claim.request().jobId(),claim.request().attemptId(),JobState.ACCEPTED,Instant.now());
        assertThat(dispatch.acknowledge(claim,accepted)).isTrue();
        assertThat(jdbc.queryForObject("SELECT state FROM collection_attempt WHERE public_id=?",String.class,claim.request().attemptId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT state FROM collection_task WHERE public_id=?",String.class,claim.request().jobId())).isEqualTo("SUCCEEDED");
    }
    @Test void retryableAndPermanentDispatchErrorsAreDurable() {
        service.manual(repositoryId,day);
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/internal/v1/jobs")).willReturn(aResponse().withStatus(503)));
        dispatcher.dispatchOne(); assertThat(countState("RETRY")).isEqualTo(1);
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/internal/v1/jobs")).willReturn(aResponse().withStatus(401)));
        dispatcher.dispatchOne(); assertThat(countState("BLOCKED")).isEqualTo(1);
        jdbc.update("UPDATE collector_dispatch SET state='READY',deadline_at=clock_timestamp()-interval '1 second',payload=jsonb_set(payload,'{deadlineAt}',to_jsonb('2000-01-01T00:00:00Z'::text)) WHERE state<>'BLOCKED'");
        for(int i=0;i<7;i++) dispatcher.dispatchOne();
        assertThat(countState("EXPIRED")).isEqualTo(7);
    }
    @Test void claimConcurrencyFencesAndRejectsMismatchedAcknowledgment() throws Exception {
        service.manual(repositoryId,day);
        try(var pool=Executors.newFixedThreadPool(4)) {
            List<Callable<Optional<DispatchRepository.Claim>>> work=new ArrayList<>();for(int i=0;i<12;i++) work.add(()->dispatch.claim(UUID.randomUUID().toString()));
            Set<Long> ids=new HashSet<>();for(var future:pool.invokeAll(work)) future.get().ifPresent(c->assertThat(ids.add(c.id())).isTrue());
            assertThat(ids).hasSize(8);
        }
        assertThat(dispatcher.dispatchOne()).isFalse();
        jdbc.update("UPDATE collector_dispatch SET locked_until=clock_timestamp()-interval '1 second'");
        var claim=dispatch.claim("recovered").orElseThrow();
        assertThatThrownBy(()->dispatch.acknowledge(claim,new JobAccepted(1,UUID.randomUUID(),UUID.randomUUID(),claim.request().attemptId(),JobState.ACCEPTED,Instant.now()))).hasMessageContaining("identity mismatch");
        assertThat(dispatch.failed(claim,"TRANSPORT",true)).isTrue();
        assertThat(dispatch.failed(claim,"TRANSPORT",true)).isFalse();
        assertThatThrownBy(()->new TransactionTemplate(tm).execute(s->dispatcher.dispatchOne())).hasMessageContaining("outside a transaction");
    }
    @Test void schedulerUsesNewServiceAndLegacyPathsAreBlocked() {
        assertThat(context.getBeansOfType(CollectionWorkerPool.class)).isEmpty();
        assertThat(context.getBeansOfType(SearchWorkerPool.class)).isEmpty();
        daily.planNow(false); assertThat(count("collection_request")).isEqualTo(1);
        assertThat(count("collection_run")).isEqualTo(1);
        assertThatThrownBy(()->legacy.planAll(day)).hasMessageContaining("/collections");
        assertThatThrownBy(()->legacySearch.planAll(day)).hasMessageContaining("/collections");
        assertThatThrownBy(()->legacyTopic.planAll(day)).hasMessageContaining("/collections");
    }
    @Test void adminPostRequiresSessionRoleAndCsrfAndReturnsCommittedIds() throws Exception {
        String url="/api/v1/admin/repositories/"+repositoryId+"/collections";
        mvc.perform(post(url).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(url).with(asUser("user")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(url).with(asUser("admin"))).andExpect(status().isForbidden());
        assertThat(count("collection_request")).isZero();
        mvc.perform(post(url).header("Idempotency-Key","manual-test").with(asUser("admin")).with(csrf())).andExpect(status().isCreated()).andExpect(jsonPath("$.requestId").isString()).andExpect(jsonPath("$.taskIds.length()").value(8));
        assertThat(count("collector_dispatch")).isEqualTo(8);
        mvc.perform(post("/api/v1/repositories/"+repositoryId+"/collect").with(asUser("admin")).with(csrf())).andExpect(status().isBadRequest());
        assertThat(count("collection_job")).isZero();
    }
    @Test void redirectsAreNotFollowed() {
        service.manual(repositoryId,day);
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/internal/v1/jobs")).willReturn(aResponse().withStatus(307).withHeader("Location",server.baseUrl()+"/untrusted")));
        dispatcher.dispatchOne(); assertThat(countState("BLOCKED")).isEqualTo(1);
        server.verify(0,anyRequestedFor(urlEqualTo("/untrusted")));
    }
    @Test void senderFailureRollsBackAckAndDoesNotLeakAnOpenTransaction() {
        service.manual(repositoryId,day); var claim=dispatch.claim("sender").orElseThrow();
        jdbc.execute("CREATE FUNCTION reject_dispatch_ack() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.state='ACKED' THEN RAISE EXCEPTION 'test crash'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER dispatch_ack_crash BEFORE UPDATE ON collector_dispatch FOR EACH ROW EXECUTE FUNCTION reject_dispatch_ack()");
        try {
            assertThatThrownBy(()->dispatch.acknowledge(claim,new JobAccepted(1,UUID.randomUUID(),claim.request().jobId(),claim.request().attemptId(),JobState.ACCEPTED,Instant.now()))).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT state FROM collection_attempt WHERE public_id=?",String.class,claim.request().attemptId())).isEqualTo("ASSIGNED");
            assertThat(jdbc.queryForObject("SELECT state FROM collection_task WHERE public_id=?",String.class,claim.request().jobId())).isEqualTo("READY");
        } finally {jdbc.execute("DROP TRIGGER dispatch_ack_crash ON collector_dispatch");jdbc.execute("DROP FUNCTION reject_dispatch_ack()");}
    }
    @Autowired AssignmentRecovery recovery;
    @Test void concurrentReassignmentCreatesOneNewAttemptAndFencesLateAcceptance() throws Exception {
        var created=service.manual(repositoryId,day);UUID job=created.taskIds().getFirst();
        UUID original=jdbc.queryForObject("SELECT current_attempt_id FROM collection_task WHERE public_id=?",UUID.class,job);
        var claim=dispatch.claim("old-sender").orElseThrow();
        jdbc.update("UPDATE collection_attempt SET assigned_at=clock_timestamp()-interval '3 minutes' WHERE public_id=?",original);
        try(var pool=Executors.newFixedThreadPool(6)) {
            List<Callable<AssignmentRecovery.Reassigned>> tasks=new ArrayList<>();for(int i=0;i<12;i++)tasks.add(()->recovery.reassign(job,original,"node-b"));
            int changed=0;for(var result:pool.invokeAll(tasks))if(result.get().changed())changed++;assertThat(changed).isEqualTo(1);
        }
        UUID current=jdbc.queryForObject("SELECT current_attempt_id FROM collection_task WHERE public_id=?",UUID.class,job);
        assertThat(current).isNotEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT state FROM collection_attempt WHERE public_id=?",String.class,original)).isEqualTo("SUPERSEDED");
        assertThat(dispatch.acknowledge(claim,new JobAccepted(1,UUID.randomUUID(),job,original,JobState.ACCEPTED,Instant.now()))).isFalse();
        JobRequest moved=json.readValue(jdbc.queryForObject("SELECT payload::text FROM collector_dispatch WHERE attempt_id=?",String.class,current),JobRequest.class);
        assertThat(moved.jobId()).isEqualTo(job);assertThat(moved.requestId()).isEqualTo(created.requestId());assertThat(moved.attemptId()).isEqualTo(current);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collection_attempt WHERE task_id=(SELECT id FROM collection_task WHERE public_id=?) AND state='ASSIGNED'",Long.class,job)).isEqualTo(1);
    }
    @Test void reassignmentPolicyAndDispatchFailureLeaveOriginalAssignmentIntact() {
        var created=service.manual(repositoryId,day);UUID job=created.taskIds().getFirst();
        UUID original=jdbc.queryForObject("SELECT current_attempt_id FROM collection_task WHERE public_id=?",UUID.class,job);
        assertThat(recovery.reassign(job,original,"node-b").reason()).isEqualTo("TOO_RECENT");
        jdbc.update("UPDATE collection_attempt SET assigned_at=clock_timestamp()-interval '3 minutes' WHERE public_id=?",original);
        jdbc.execute("CREATE FUNCTION fail_reassignment() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test crash'; END $$");
        jdbc.execute("CREATE TRIGGER reassignment_crash BEFORE INSERT ON collector_dispatch FOR EACH ROW EXECUTE FUNCTION fail_reassignment()");
        try {
            assertThatThrownBy(()->recovery.reassign(job,original,"node-b")).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT current_attempt_id FROM collection_task WHERE public_id=?",UUID.class,job)).isEqualTo(original);
            assertThat(jdbc.queryForObject("SELECT state FROM collection_attempt WHERE public_id=?",String.class,original)).isEqualTo("ASSIGNED");
        }finally{jdbc.execute("DROP TRIGGER reassignment_crash ON collector_dispatch");jdbc.execute("DROP FUNCTION fail_reassignment()");}
        assertThat(recovery.reassign(job,original,"node-b").changed()).isTrue();
    }
    void accept(JobRequest job) {
        var accepted=new JobAccepted(1,UUID.randomUUID(),job.jobId(),job.attemptId(),JobState.ACCEPTED,Instant.now());
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/internal/v1/jobs")).willReturn(aResponse().withStatus(201).withHeader("Content-Type","application/json").withBody(json.writeValueAsString(accepted))));
    }
    long count(String table) {return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    long countState(String state) {return jdbc.queryForObject("SELECT count(*) FROM collector_dispatch WHERE state=?",Long.class,state);}
    RequestPostProcessor asUser(String sub) {
        AppUser user=users.findByIssuerAndSub(MockUserCatalog.ISSUER,sub).orElseThrow();
        return authentication(UsernamePasswordAuthenticationToken.authenticated(new AppUserPrincipal(user.id(),user.displayName()),null,SecurityAuthorities.from(user)));
    }

    @Test void nodeStatusClientUsesConfiguredAuthenticationAndNeverFollowsRedirects(){
        var client=new CollectorNodeClient(context.getBean(OrchestrationProperties.class),json);
        server.stubFor(get(urlEqualTo("/internal/v1/status")).willReturn(okJson("{\"schemaVersion\":1,\"accepting\":true,\"executionEnabled\":true}")));
        assertThat(client.ready("node-a",server.baseUrl())).isTrue();server.verify(getRequestedFor(urlEqualTo("/internal/v1/status")).withHeader("Authorization",equalTo("Bearer service-test-secret")));
        server.stubFor(get(urlEqualTo("/internal/v1/status")).willReturn(aResponse().withStatus(307).withHeader("Location",server.baseUrl()+"/leak")));
        assertThatThrownBy(()->client.ready("node-a",server.baseUrl())).hasMessageContaining("unavailable");server.verify(0,anyRequestedFor(urlEqualTo("/leak")));
        assertThatThrownBy(()->client.ready("node-a",server.baseUrl()+"/other")).isInstanceOf(IllegalStateException.class);
    }
    @Test void reassignmentLimitTerminatesTaskInsteadOfLoopingForever(){
        var created=service.manual(repositoryId,day);UUID job=created.taskIds().getFirst();UUID original=jdbc.queryForObject("SELECT current_attempt_id FROM collection_task WHERE public_id=?",UUID.class,job);
        jdbc.update("UPDATE collection_attempt SET assigned_at=clock_timestamp()-interval '3 minutes',attempt_no=3 WHERE public_id=?",original);
        assertThat(recovery.reassign(job,original,"node-b").reason()).isEqualTo("ATTEMPT_LIMIT");assertThat(jdbc.queryForObject("SELECT state FROM collection_task WHERE public_id=?",String.class,job)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM collector_dispatch WHERE attempt_id=?",String.class,original)).isEqualTo("ATTEMPT_LIMIT");
    }
}
