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
    "rgm.orchestration.nodes[1].key=node-b","rgm.orchestration.nodes[1].pool=spare","rgm.orchestration.nodes[1].endpoint=https://node-b.example","rgm.orchestration.nodes[1].enabled=true","rgm.orchestration.nodes[1].resolve-token="+CredentialVaultIT.NODE_B})
@AutoConfigureMockMvc
class CredentialVaultIT extends AbstractPostgresTest {
    static final String NODE_A="node-a-resolution-service-token-012345",NODE_B="node-b-resolution-service-token-012345";
    @Autowired JdbcTemplate jdbc;@Autowired JdbcClient jdbcClient;@Autowired CredentialVault vault;@Autowired CollectionRequestService requests;
    @Autowired MockMvc mvc;@Autowired JsonMapper json;@Autowired UserAccountService users;@Autowired MockUserCatalog catalog;
    long repositoryId;AuthorizationScope scope;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE github_credential_version,github_credential,repository_credential,collection_request_command,idempotency_records,collector_result_receipt,collector_dispatch,collection_attempt,collection_task,collection_request,collector_node RESTART IDENTITY");
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
}
