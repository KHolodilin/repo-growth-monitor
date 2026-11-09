package com.kholodilin.repogrowth.credentials;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.credentials.*;
import com.kholodilin.repogrowth.orchestration.CollectionRequestService;
import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.mock.MockUserCatalog;
import com.kholodilin.repogrowth.auth.security.*;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"rgm.orchestration.enabled=true","rgm.credentials.enabled=true","rgm.credentials.active-key-id=test",
    "rgm.credentials.encryption-keys.test=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "rgm.orchestration.nodes[0].key=node-a","rgm.orchestration.nodes[0].pool=default","rgm.orchestration.nodes[0].endpoint=https://node-a.example","rgm.orchestration.nodes[0].enabled=true","rgm.orchestration.nodes[0].resolve-token="+CredentialVaultIT.NODE_A,
    "rgm.orchestration.nodes[1].key=node-b","rgm.orchestration.nodes[1].pool=default","rgm.orchestration.nodes[1].endpoint=https://node-b.example","rgm.orchestration.nodes[1].enabled=true","rgm.orchestration.nodes[1].resolve-token="+CredentialVaultIT.NODE_B})
@AutoConfigureMockMvc
class CredentialVaultIT extends AbstractPostgresTest {
    static final String NODE_A="node-a-resolution-service-token-012345",NODE_B="node-b-resolution-service-token-012345";
    @Autowired JdbcTemplate jdbc;@Autowired JdbcClient jdbcClient;@Autowired CredentialVault vault;@Autowired CollectionRequestService requests;
    @Autowired MockMvc mvc;@Autowired JsonMapper json;@Autowired UserAccountService users;@Autowired MockUserCatalog catalog;
    long repositoryId;AuthorizationScope scope;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE collection_history_mapping,collector_snapshot_header,github_budget_receipt,github_request_permit,github_budget,github_credential_version,github_credential,repository_credential,collection_request_command,idempotency_records,collector_result_receipt,collector_dispatch,collection_attempt,collection_task,collection_request,collector_node RESTART IDENTITY");
        wipeRepositoryData(jdbcClient);catalog.syncMissingUsers();
        long owner=jdbc.queryForObject("INSERT INTO github_owner(github_id,login,owner_type) VALUES (101,'KHolodilin','USER') RETURNING id",Long.class);
        repositoryId=jdbc.queryForObject("INSERT INTO repository(github_id,owner_id,name,full_name,visibility,tracking_enabled) VALUES (202,?,'repo-growth-monitor','KHolodilin/repo-growth-monitor','PUBLIC',true) RETURNING id",Long.class,owner);
        vault.rotate("github-main","github-secret-v1",null);scope=vault.bind(repositoryId,"github-main");
    }
    JobRequest task() {requests.manual(repositoryId,LocalDate.now());return json.readValue(jdbc.queryForObject("SELECT payload::text FROM collector_dispatch ORDER BY id LIMIT 1",String.class),JobRequest.class);}
    CredentialRequest credentialRequest(JobRequest job){return new CredentialRequest(1,job.jobId(),job.attemptId());}
    String resolve(CredentialRequest request,String token,int expected) throws Exception {
        return mvc.perform(post("/internal/v1/credentials/resolve").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(request)))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    RequestPostProcessor user(String name) {
        var user=users.findByIssuerAndSub(MockUserCatalog.ISSUER,name).orElseThrow();return authentication(UsernamePasswordAuthenticationToken.authenticated(new AppUserPrincipal(user.id(),user.displayName()),null,SecurityAuthorities.from(user)));
    }
    @Test void credentialsAreEncryptedAndOnlyAssignedNodeCanResolveCurrentContext() throws Exception {
        var job=task();assertThat(job.authorization()).isEqualTo(scope);
        var lease=json.readValue(resolve(credentialRequest(job),NODE_A,200),CredentialLease.class);
        assertThat(lease.token()).isEqualTo("github-secret-v1");assertThat(lease.authorizationContext()).isEqualTo(scope.context());assertThat(lease.cacheValidUntil()).isBeforeOrEqualTo(job.deadlineAt());
        assertThat(jdbc.queryForObject("SELECT to_jsonb(v)::text FROM github_credential_version v",String.class)).doesNotContain("github-secret-v1");
        assertThat(jdbc.queryForObject("SELECT string_agg(payload::text,' ') FROM collector_dispatch",String.class)).doesNotContain("github-secret-v1");
        assertThat(jdbc.queryForObject("SELECT string_agg(configuration_snapshot::text,' ') FROM collection_task",String.class)).doesNotContain("github-secret-v1");
        resolve(credentialRequest(job),NODE_B,403);resolve(new CredentialRequest(1,UUID.randomUUID(),job.attemptId()),NODE_A,403);
        mvc.perform(post("/internal/v1/credentials/resolve").header("Authorization","Bearer "+NODE_A).contentType("application/json").content(json.writeValueAsString(credentialRequest(job))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void invalidServiceCredentialsAndProductSessionsCannotReadSecrets() throws Exception {
        var job=task();resolve(credentialRequest(job),"bad-token",401);
        mvc.perform(post("/internal/v1/credentials/resolve").with(user("admin")).with(csrf()).contentType("application/json").content(json.writeValueAsString(credentialRequest(job)))).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/credentials/resolve").contentType("application/json").content("not-json")).andExpect(status().isUnauthorized());
    }
    @Test void rotationIsVersionedAndExpiryRevocationAndRebindingDenyOldAssignments() throws Exception {
        var job=task();var first=vault.resolve("node-a",credentialRequest(job));
        Instant expiry=Instant.now().plusSeconds(60);assertThat(vault.rotate("github-main","github-secret-v2",expiry).version()).isEqualTo(2);
        var next=vault.resolve("node-a",credentialRequest(job));assertThat(next.token()).isEqualTo("github-secret-v2");assertThat(next.credentialVersion()).isNotEqualTo(first.credentialVersion());assertThat(next.cacheValidUntil()).isBeforeOrEqualTo(expiry);
        vault.revoke("github-main");resolve(credentialRequest(job),NODE_A,403);
        vault.rotate("github-main","github-secret-v3",null);vault.bind(repositoryId,"github-main");resolve(credentialRequest(job),NODE_A,403);
    }
    @Test void expiredAndSupersededAttemptsNeverResolve() throws Exception {
        var job=task();jdbc.update("UPDATE collector_dispatch SET deadline_at=clock_timestamp()-interval '1 second'");resolve(credentialRequest(job),NODE_A,403);
        jdbc.update("UPDATE collector_dispatch SET deadline_at=clock_timestamp()+interval '1 hour'");jdbc.update("UPDATE collection_attempt SET state='CANCELLED'");resolve(credentialRequest(job),NODE_A,403);
    }
    @Test void failoverBResolvesIndependentlyAndAHasNoAccessToTheNewAssignment() throws Exception {
        var original=task();vault.resolve("node-a",credentialRequest(original));UUID attempt=UUID.randomUUID();
        long node=jdbc.queryForObject("INSERT INTO collector_node(node_key,pool,endpoint,enabled) VALUES ('node-b','spare','https://node-b.example',true) RETURNING id",Long.class);
        long task=jdbc.queryForObject("SELECT id FROM collection_task WHERE public_id=?",Long.class,original.jobId());
        jdbc.update("UPDATE collection_attempt SET state='CANCELLED' WHERE public_id=?",original.attemptId());
        jdbc.update("INSERT INTO collection_attempt(public_id,task_id,attempt_no,node_id) VALUES (?,?,2,?)",attempt,task,node);
        jdbc.update("UPDATE collection_task SET current_attempt_id=? WHERE id=?",attempt,task);
        var moved=new JobRequest(1,original.requestId(),original.jobId(),attempt,original.type(),original.repository(),original.businessDate(),original.configurationRevision(),original.parameters(),original.destinationRef(),original.deadlineAt(),original.authorization());
        jdbc.update("INSERT INTO collector_dispatch(attempt_id,endpoint,payload,deadline_at) VALUES (?,'https://node-b.example',?::jsonb,?)",attempt,json.writeValueAsString(moved),java.sql.Timestamp.from(moved.deadlineAt()));
        resolve(credentialRequest(original),NODE_A,403);resolve(credentialRequest(moved),NODE_A,403);resolve(credentialRequest(moved),NODE_B,200);
        var properties=new CollectorCredentialProperties(false,null,100,1000,null,null,null,false,List.of());
        var b=new CredentialCache((origin,request)->vault.resolve("node-b",request),properties,Clock.systemUTC(),com.github.benmanes.caffeine.cache.Ticker.systemTicker());
        assertThat(b.mappingSize()).isZero();assertThat(b.get("rgm",moved).token()).isEqualTo("github-secret-v1");
    }
    @Test void adminMutationsRequireAdminAndCsrfAndNeverReturnPlaintext() throws Exception {
        String payload=json.writeValueAsString(new CredentialController.Rotation("admin-token-secret",null));
        mvc.perform(put("/api/v1/admin/credentials/other").with(user("user")).with(csrf()).contentType("application/json").content(payload)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/credentials/other").with(user("admin")).contentType("application/json").content(payload)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/credentials/other").with(user("admin")).with(csrf()).contentType("application/json").content(payload)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.token").doesNotExist());
        mvc.perform(put("/api/v1/admin/repositories/"+repositoryId+"/credential").with(user("admin")).with(csrf()).contentType("application/json").content("{\"ref\":\"other\"}")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/admin/credentials/other").with(user("admin")).with(csrf())).andExpect(status().isNoContent());
    }
    @Test void encryptedPayloadTamperingFailsClosedWithoutLeakingSecrets() throws Exception {
        var job=task();jdbc.update("UPDATE github_credential_version SET ciphertext=set_byte(ciphertext,0,get_byte(ciphertext,0)#1)");
        assertThat(resolve(credentialRequest(job),NODE_A,503)).doesNotContain("github-secret-v1");
    }
    @Autowired SharedGitHubBudget budget;
    @Test void sharedBudgetPersistsPacingConcurrencyAndCooldownAcrossServiceRestart() {
        var job=task();var assignment=credentialRequest(job);UUID request=UUID.randomUUID();
        var acquired=budget.acquire("node-a",new SharedGitHubBudget.Acquisition(request,assignment,"core"));
        assertThat(acquired.permitId()).isEqualTo(request);
        assertThat(budget.acquire("node-a",new SharedGitHubBudget.Acquisition(request,assignment,"core")).permitId()).isEqualTo(request);
        assertThat(jdbc.queryForObject("SELECT remaining FROM github_budget",Long.class)).isEqualTo(2999);
        assertThat(budget.acquire("node-a",new SharedGitHubBudget.Acquisition(UUID.randomUUID(),assignment,"search")).permitId()).isNull();
        Instant retry=Instant.now().plusSeconds(600);
        assertThat(budget.report("node-a",new SharedGitHubBudget.Report(request,429,0L,retry,retry))).isTrue();
        assertThat(budget.report("node-a",new SharedGitHubBudget.Report(request,200,5000L,null,null))).isFalse();
        var restarted=new SharedGitHubBudget(jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()),vault);
        var blocked=restarted.acquire("node-a",new SharedGitHubBudget.Acquisition(UUID.randomUUID(),assignment,"core"));
        assertThat(blocked.permitId()).isNull();assertThat(blocked.retryAt()).isAfterOrEqualTo(retry);
        assertThat(jdbc.queryForObject("SELECT remaining FROM github_budget WHERE resource='core'",Long.class)).isZero();
    }

    @Autowired com.kholodilin.repogrowth.orchestration.CollectorResultIngestion ingestion;
    @Autowired com.kholodilin.repogrowth.orchestration.AssignmentRecovery recovery;
    @Autowired com.kholodilin.repogrowth.orchestration.DispatchRepository dispatch;
    JobRequest typedTask(TaskType type){requests.manual(repositoryId,LocalDate.now());return json.readValue(jdbc.queryForObject("SELECT d.payload::text FROM collector_dispatch d JOIN collection_task t ON t.current_attempt_id=d.attempt_id WHERE t.task_type=? ORDER BY t.id LIMIT 1",String.class,type.name()),JobRequest.class);}
    ResultDelivery result(JobRequest job,int stars){
        String repository="{\"id\":202,\"private\":false,\"fork\":false,\"archived\":false,\"name\":\"repo-growth-monitor\",\"full_name\":\"KHolodilin/repo-growth-monitor\",\"stargazers_count\":"+stars+",\"subscribers_count\":1,\"forks_count\":2,\"open_issues_count\":0}";
        java.util.Map<String,String> metadata=new java.util.TreeMap<>();metadata.put("repository",repository);metadata.put("contributors","[]");metadata.put("issues","[]");metadata.put("pulls","[]");metadata.put("releases","[]");
        java.util.List<TrafficDay> traffic=job.type()==TaskType.TRAFFIC?java.util.List.of(new TrafficDay(LocalDate.now(),1L,0L,2L,0L)):java.util.List.of();
        java.util.List<SourceObservation> sources=job.type()==TaskType.REFERRERS?java.util.List.of(new SourceObservation("example.com",null,1,1)):job.type()==TaskType.POPULAR_PATHS?java.util.List.of(new SourceObservation("/KHolodilin/repo-growth-monitor/issues","Issues",1,1)):java.util.List.of();
        boolean rank=job.type()==TaskType.SEARCH_QUERY || job.type()==TaskType.TOPIC_WATCH;
        if(rank)metadata.put("search","{\"total_count\":1,\"items\":[{\"id\":202,\"full_name\":\"KHolodilin/repo-growth-monitor\",\"stargazers_count\":7,\"forks_count\":2,\"owner\":{\"id\":101,\"login\":\"KHolodilin\",\"type\":\"User\"}}]}");
        var snapshot=new Snapshot(Instant.now(),true,java.util.Map.of(Metric.STARS,(long)stars,Metric.WATCHERS,1L,Metric.FORKS,2L,Metric.CONTRIBUTORS,0L),java.util.List.of(),traffic,sources,rank?java.util.List.of(new RankedRepository("202","KHolodilin/repo-growth-monitor",1,7,2)):java.util.List.of(),rank?1L:null,rank?1:null,metadata,java.util.List.of());
        return new ResultDelivery(1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),job.jobId(),job.attemptId(),job.type(),"202",JobState.SUCCEEDED,snapshot,null);
    }
    String callback(ResultDelivery result,String token,int expected)throws Exception{
        return mvc.perform(post("/internal/v1/collector-results").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(result))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    @Test void resultReplayConflictAndCleanupAreAtomicAndPermanent()throws Exception{
        var job=typedTask(TaskType.STATS);var delivered=result(job,7);callback(delivered,NODE_A,204);callback(delivered,NODE_A,204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collector_result_receipt",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT stars FROM repository WHERE id=?",Integer.class,repositoryId)).isEqualTo(7);
        var changed=new ResultDelivery(1,delivered.eventId(),delivered.resultId(),delivered.collectorJobId(),delivered.jobId(),delivered.attemptId(),delivered.type(),delivered.repositoryGithubId(),delivered.status(),result(job,8).snapshot(),null);callback(changed,NODE_A,409);
        jdbc.update("UPDATE idempotency_records SET expires_at=clock_timestamp()-interval '1 second'");new com.kholodilin.idempotency.jdbc.JdbcIdempotencyPersistenceCleanup(jdbc.getDataSource()).deleteExpired(Instant.now(),100);
        callback(delivered,NODE_A,204);callback(changed,NODE_A,409);assertThat(jdbc.queryForObject("SELECT count(*) FROM collector_result_receipt",Long.class)).isEqualTo(1);
    }
    @Test void callbackBeforeDispatchAckDoesNotDowngradeTask()throws Exception{
        var job=typedTask(TaskType.STATS);var delivered=result(job,7);callback(delivered,NODE_A,204);
        var claim=dispatch.claim("sender").orElseThrow();assertThat(dispatch.acknowledge(claim,new JobAccepted(1,delivered.collectorJobId(),job.jobId(),job.attemptId(),JobState.ACCEPTED,Instant.now()))).isTrue();
        assertThat(jdbc.queryForObject("SELECT state FROM collection_task WHERE public_id=?",String.class,job.jobId())).isEqualTo("SUCCEEDED");
    }
    @Test void receiptFailureRollsBackSnapshotAndIdempotency()throws Exception{
        var job=typedTask(TaskType.STATS);var delivered=result(job,7);
        jdbc.execute("CREATE FUNCTION fail_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test crash'; END $$");jdbc.execute("CREATE TRIGGER receipt_crash BEFORE INSERT ON collector_result_receipt FOR EACH ROW EXECUTE FUNCTION fail_receipt()");
        try{assertThatThrownBy(()->ingestion.apply("node-a",delivered)).isInstanceOf(RuntimeException.class);assertThat(jdbc.queryForObject("SELECT stars FROM repository WHERE id=?",Integer.class,repositoryId)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM collector_result_receipt",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records",Long.class)).isZero();}
        finally{jdbc.execute("DROP TRIGGER receipt_crash ON collector_result_receipt");jdbc.execute("DROP FUNCTION fail_receipt()");}callback(delivered,NODE_A,204);
    }
    @Test void simultaneousTaskCompletionExtendsAllLegacyHistoryFamilies()throws Exception{
        jdbc.update("INSERT INTO search_query(repository_id,name,query,enabled,result_limit) VALUES (?,'rank','spring',true,50)",repositoryId);jdbc.update("UPDATE repository SET language='Java' WHERE id=?",repositoryId);jdbc.update("INSERT INTO repository_topics VALUES (?,'spring')",repositoryId);
        requests.manual(repositoryId,LocalDate.now());var jobs=jdbc.queryForList("SELECT payload::text FROM collector_dispatch ORDER BY id",String.class).stream().map(raw->json.readValue(raw,JobRequest.class)).toList();assertThat(jobs).hasSize(8);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(8)){
            java.util.List<java.util.concurrent.Callable<String>> work=new java.util.ArrayList<>();for(var job:jobs)work.add(()->ingestion.apply("node-a",result(job,7)).outcome());for(var future:pool.invokeAll(work))assertThat(future.get()).isEqualTo("APPLIED");
        }
        assertThat(jdbc.queryForObject("SELECT state FROM collection_request",String.class)).isEqualTo("SUCCEEDED");assertThat(jdbc.queryForObject("SELECT count(*) FROM collection_history_mapping",Long.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repository_daily_stats",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM traffic_daily",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM search_run WHERE status='SUCCESS'",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM topic_run WHERE status='SUCCESS'",Long.class)).isEqualTo(2);
    }
    @Test void oldNodeLateCallbackIsIgnoredAfterBHasApplied()throws Exception{
        var original=typedTask(TaskType.STATS);var late=result(original,7);jdbc.update("UPDATE collection_attempt SET assigned_at=clock_timestamp()-interval '3 minutes' WHERE public_id=?",original.attemptId());var moved=recovery.reassign(original.jobId(),original.attemptId(),"node-b");assertThat(moved.changed()).isTrue();
        callback(late,NODE_A,204);assertThat(jdbc.queryForObject("SELECT outcome FROM collector_result_receipt WHERE event_id=?",String.class,late.eventId())).isEqualTo("IGNORED");assertThat(jdbc.queryForObject("SELECT stars FROM repository WHERE id=?",Integer.class,repositoryId)).isZero();
        JobRequest current=json.readValue(jdbc.queryForObject("SELECT payload::text FROM collector_dispatch WHERE attempt_id=?",String.class,moved.attemptId()),JobRequest.class);callback(result(current,99),NODE_B,204);callback(late,NODE_A,204);assertThat(jdbc.queryForObject("SELECT stars FROM repository WHERE id=?",Integer.class,repositoryId)).isEqualTo(99);
    }
    @Test void resultAuthorizationRunsBeforeReplay()throws Exception{
        var job=typedTask(TaskType.STATS);var delivered=result(job,7);callback(delivered,NODE_A,204);callback(delivered,NODE_B,403);callback(delivered,"bad-token",401);
    }

    @Test void credentialAliasesShareOnePrincipalBudget(){
        var job=task();vault.rotate("github-main","github-secret-v2",null,"github:user:101");var grant=budget.acquire("node-a",new SharedGitHubBudget.Acquisition(UUID.randomUUID(),credentialRequest(job),"core"));
        budget.report("node-a",new SharedGitHubBudget.Report(grant.permitId(),429,0L,null,Instant.now().plusSeconds(600)));
        vault.rotate("other","another-token",null,"github:user:101");vault.bind(repositoryId,"other");jdbc.update("UPDATE collection_request SET state='SUCCEEDED'");
        requests.manual(repositoryId,LocalDate.now());var next=json.readValue(jdbc.queryForObject("SELECT payload::text FROM collector_dispatch ORDER BY id DESC LIMIT 1",String.class),JobRequest.class);assertThat(budget.acquire("node-a",new SharedGitHubBudget.Acquisition(UUID.randomUUID(),credentialRequest(next),"core")).permitId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_budget WHERE identity_key='github:user:101'",Long.class)).isEqualTo(1);
    }

    @Test void durableDiscoveryExtendsCachedListAndCoalescesIntoCurrentRequest()throws Exception{
        var original=task();var refreshed=requests.refresh(LocalDate.now());assertThat(refreshed).hasSize(1);assertThat(refreshed.getFirst().requestId()).isEqualTo(original.requestId());
        requests.refresh(LocalDate.now());assertThat(jdbc.queryForObject("SELECT count(*) FROM collection_task WHERE task_type='DISCOVER_REPOSITORIES'",Long.class)).isEqualTo(1);
        JobRequest discovery=json.readValue(jdbc.queryForObject("SELECT payload::text FROM collector_dispatch d JOIN collection_task t ON t.current_attempt_id=d.attempt_id WHERE t.task_type='DISCOVER_REPOSITORIES'",String.class),JobRequest.class);
        String remote="[{\"id\":303,\"stargazers_count\":0,\"forks_count\":0,\"open_issues_count\":0,\"private\":false,\"fork\":false,\"archived\":false,\"name\":\"new-project\",\"full_name\":\"KHolodilin/new-project\",\"owner\":{\"id\":101,\"login\":\"KHolodilin\",\"type\":\"User\"}}]";
        var snapshot=new Snapshot(Instant.now(),true,Map.of(),List.of(),List.of(),List.of(),List.of(),null,null,Map.of("repositories",remote),List.of());
        ingestion.apply("node-a",new ResultDelivery(1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),discovery.jobId(),discovery.attemptId(),discovery.type(),"202",JobState.SUCCEEDED,snapshot,null));
        assertThat(jdbc.queryForObject("SELECT name FROM repository WHERE github_id=303",String.class)).isEqualTo("new-project");
        mvc.perform(post("/api/v1/admin/repositories/refresh").with(user("admin"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/repositories/refresh").with(user("admin")).with(csrf())).andExpect(status().isCreated());
    }
    @Autowired com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository trafficRepository;
    @Test void emptySnapshotReplacesOldNonemptyCardAndHistoryKeepsItsDate(){
        var job=typedTask(TaskType.REFERRERS);trafficRepository.insertReferrers(repositoryId,LocalDate.now().minusDays(1),Instant.now().minusSeconds(86400),"old.example",7,4);
        var original=result(job,7);var empty=new Snapshot(Instant.now(),true,Map.of(),List.of(),List.of(),List.of(),List.of(),null,null,Map.of(),List.of());
        ingestion.apply("node-a",new ResultDelivery(1,original.eventId(),original.resultId(),original.collectorJobId(),job.jobId(),job.attemptId(),job.type(),"202",JobState.SUCCEEDED,empty,null));
        assertThat(trafficRepository.latestReferrerSnapshot(repositoryId).rows()).isEmpty();assertThat(trafficRepository.latestReferrerSnapshot(repositoryId).snapshotAt()).isNotNull();
        var pivot=com.kholodilin.repogrowth.traffic.SnapshotHistoryMath.pivot(trafficRepository.referrerSnapshotsForDelta(repositoryId,LocalDate.now().minusDays(1),LocalDate.now()),LocalDate.now().minusDays(1),LocalDate.now());assertThat(pivot.dates()).containsExactly(LocalDate.now().minusDays(1),LocalDate.now());
    }
}
