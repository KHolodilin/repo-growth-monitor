package com.kholodilin.repogrowth.orchestration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.kholodilin.idempotency.*;
import com.kholodilin.idempotency.exception.MissingTransactionException;
import com.kholodilin.idempotency.jdbc.JdbcIdempotencyPersistenceCleanup;
import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.mock.MockUserCatalog;
import com.kholodilin.repogrowth.auth.security.*;
import com.kholodilin.repogrowth.orchestration.idempotency.InvalidIdempotencyKeyException;
import com.kholodilin.repogrowth.orchestration.idempotency.ScopedIdempotency;
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
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
      "rgm.orchestration.enabled=true",
      "rgm.orchestration.nodes[0].key=collector",
      "rgm.orchestration.nodes[0].pool=default",
      "rgm.orchestration.nodes[0].endpoint=https://collector.example",
      "rgm.orchestration.nodes[0].enabled=true"
    })
@AutoConfigureMockMvc
class IdempotencyIT extends AbstractPostgresTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JdbcClient jdbcClient;
  @Autowired UserAccountService users;
  @Autowired MockUserCatalog catalog;
  @Autowired JsonMapper json;
  @Autowired IdempotentCollectionRequests requests;

  @Autowired
  @Qualifier("rgmIdempotency")
  IdempotencyService rgmIdempotency;

  @Autowired PlatformTransactionManager tm;
  @Autowired DataSource productDs;
  long repositoryId, otherId, adminId;

  @BeforeEach
  void reset() {
    jdbc.execute(
        "TRUNCATE collection_history_mapping,collector_snapshot_header,idempotency_records,collection_request_command,collector_result_receipt,collector_dispatch,collection_attempt,collection_task,collection_request,collector_node RESTART IDENTITY");
    wipeRepositoryData(jdbcClient);
    catalog.syncMissingUsers();
    adminId = users.findByIssuerAndSub(MockUserCatalog.ISSUER, "admin").orElseThrow().id();
    long owner =
        jdbc.queryForObject(
            "INSERT INTO github_owner(github_id,login,owner_type) VALUES (101,'KHolodilin','USER') RETURNING id",
            Long.class);
    repositoryId = insertRepository(202, owner, "repo-growth-monitor");
    otherId = insertRepository(303, owner, "other");
  }

  long insertRepository(long githubId, long owner, String name) {
    return jdbc.queryForObject(
        "INSERT INTO repository(github_id,owner_id,name,full_name,visibility,tracking_enabled) VALUES (?,?,?,?,'PUBLIC',true) RETURNING id",
        Long.class,
        githubId,
        owner,
        name,
        "KHolodilin/" + name);
  }

  String manualUrl(long id) {
    return "/api/v1/admin/repositories/" + id + "/collections";
  }

  RequestPostProcessor user(String sub) {
    var user = users.findByIssuerAndSub(MockUserCatalog.ISSUER, sub).orElseThrow();
    return authentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new AppUserPrincipal(user.id(), user.displayName()),
            null,
            SecurityAuthorities.from(user)));
  }

  @Test
  void scopedKeysStayByteCompatibleOnFixedExamples() {
    assertThat(ScopedIdempotency.key("user:1", "manual-key"))
        .isEqualTo(
            new com.kholodilin.idempotency.jackson.CanonicalJsonFingerprintStrategy()
                .calculate(List.of("user:1", "manual-key")));
    assertThat(ScopedIdempotency.key("node:collector", "11111111-1111-1111-1111-111111111111"))
        .isEqualTo(
            new com.kholodilin.idempotency.jackson.CanonicalJsonFingerprintStrategy()
                .calculate(List.of("node:collector", "11111111-1111-1111-1111-111111111111")));
  }

  @Test
  void invalidIdempotencyKeyKeepsValidationErrorShape() throws Exception {
    assertThatThrownBy(() -> ScopedIdempotency.key("user:1", "invalid key"))
        .isInstanceOf(InvalidIdempotencyKeyException.class)
        .hasMessage("Idempotency-Key must contain 1..128 ASCII token characters");
    mvc.perform(
            post(manualUrl(repositoryId))
                .with(user("admin"))
                .with(csrf())
                .header("Idempotency-Key", "invalid key"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value("Invalid request parameters or Idempotency-Key"));
  }

  @Test
  void concurrentDuplicatesUseTheStarter() throws Exception {
    try (var pool = Executors.newFixedThreadPool(5)) {
      List<Callable<CollectionRequestService.Created>> productTasks = new ArrayList<>();
      for (int i = 0; i < 10; i++)
        productTasks.add(
            () -> requests.create(adminId, "manual-key", repositoryId, LocalDate.now()));
      Set<UUID> requestIds = new HashSet<>();
      for (var f : pool.invokeAll(productTasks)) requestIds.add(f.get().requestId());
      assertThat(requestIds).hasSize(1);
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Long.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM collection_task", Long.class))
        .isEqualTo(5);
  }

  @Test
  void productReplaySurvivesCompletionCleanupAndCoalescedAliases() throws Exception {
    String a =
        mvc.perform(
                post(manualUrl(repositoryId))
                    .with(user("admin"))
                    .with(csrf())
                    .header("Idempotency-Key", "a"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String b =
        mvc.perform(
                post(manualUrl(repositoryId))
                    .with(user("admin"))
                    .with(csrf())
                    .header("Idempotency-Key", "b"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(a).get("requestId")).isEqualTo(json.readTree(b).get("requestId"));
    jdbc.update("UPDATE collection_request SET state='SUCCEEDED'");
    expire(jdbc, productDs);
    for (var entry : Map.of("a", a, "b", b).entrySet()) {
      String replay =
          mvc.perform(
                  post(manualUrl(repositoryId))
                      .with(user("admin"))
                      .with(csrf())
                      .header("Idempotency-Key", entry.getKey()))
              .andExpect(status().isCreated())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(replay).isEqualTo(entry.getValue());
    }
    mvc.perform(
            post(manualUrl(otherId))
                .with(user("admin"))
                .with(csrf())
                .header("Idempotency-Key", "a"))
        .andExpect(status().isConflict());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM collection_request", Long.class))
        .isEqualTo(1);
  }

  @Test
  void productConflictsAndRevokedAuthorityCannotReplay() throws Exception {
    requests.create(adminId, "key", repositoryId, LocalDate.now());
    mvc.perform(
            post(manualUrl(otherId))
                .with(user("admin"))
                .with(csrf())
                .header("Idempotency-Key", "key"))
        .andExpect(status().isConflict());
    mvc.perform(post(manualUrl(repositoryId)).with(user("admin")).with(csrf()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post(manualUrl(repositoryId))
                .with(user("admin"))
                .with(csrf())
                .header("Idempotency-Key", "invalid key"))
        .andExpect(status().isBadRequest());
    users.revokeAdmin(adminId);
    try {
      mvc.perform(
              post(manualUrl(repositoryId))
                  .with(user("admin"))
                  .with(csrf())
                  .header("Idempotency-Key", "key"))
          .andExpect(status().isForbidden());
      assertThatThrownBy(() -> requests.create(adminId, "key", repositoryId, LocalDate.now()))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    } finally {
      users.grantAdmin(adminId);
    }
  }

  @Test
  void productActorScopeDoesNotCollide() {
    var other = users.findByIssuerAndSub(MockUserCatalog.ISSUER, "user").orElseThrow();
    users.grantAdmin(other.id());
    try {
      var a = requests.create(adminId, "same-key", repositoryId, LocalDate.now());
      var b = requests.create(other.id(), "same-key", otherId, LocalDate.now());
      assertThat(a.requestId()).isNotEqualTo(b.requestId());
      assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Long.class))
          .isEqualTo(2);
    } finally {
      users.revokeAdmin(other.id());
    }
  }

  @Test
  void productIdempotencyRequiresOwningDatasourceTransaction() {
    assertThatThrownBy(() ->
            rgmIdempotency
                .operation("TEST")
                .key("same")
                .request("x")
                .execute(String.class, () -> ExecutionResult.success("bad")))
        .isInstanceOf(MissingTransactionException.class);
    new TransactionTemplate(tm)
        .execute(s ->
            rgmIdempotency
                .operation("TEST")
                .key("same")
                .request("x")
                .execute(String.class, () -> ExecutionResult.success("product")));
    assertThat(
            jdbc.queryForObject(
                "SELECT result_payload::text FROM idempotency_records", String.class))
        .contains("product");
  }

  @Test
  void productActionFailureRollsBackReplayRecordAndAllBusinessWrites() {
    jdbc.update(
        "INSERT INTO search_query(repository_id,name,query,enabled,result_limit) VALUES (?,'bad','stars:>10',true,101)",
        repositoryId);
    assertThatThrownBy(() -> requests.create(adminId, "rollback", repositoryId, LocalDate.now()))
        .isInstanceOf(IllegalArgumentException.class);
    for (String table :
        List.of(
            "idempotency_records",
            "collection_request_command",
            "collection_request",
            "collection_task",
            "collector_dispatch"))
      assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
    jdbc.update("UPDATE search_query SET result_limit=50");
    assertThat(requests.create(adminId, "rollback", repositoryId, LocalDate.now()).taskIds())
        .hasSize(6);
  }

  void expire(JdbcTemplate jdbc, DataSource ds) {
    jdbc.update("UPDATE idempotency_records SET expires_at=clock_timestamp()-interval '1 second'");
    assertThat(new JdbcIdempotencyPersistenceCleanup(ds).deleteExpired(Instant.now(), 100))
        .isPositive();
  }
}
