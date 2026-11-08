package com.kholodilin.repogrowth.orchestration;

import com.kholodilin.collector.acceptance.CollectorJobController;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.repogrowth.auth.application.UserAccountService;
import java.time.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.task.scheduling.enabled=false","collector.http.clients[0].origin=rgm","collector.http.clients[0].token="+CollectorHttpRoleIT.TOKEN})
@ActiveProfiles({"collector","collector-http"})
@AutoConfigureMockMvc
class CollectorHttpRoleIT {
    static final String TOKEN="collector-http-role-token-0123456789";
    static PostgreSQLContainer database=new PostgreSQLContainer("postgres:18.6");
    static {database.start();}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("collector.datasource.url",database::getJdbcUrl);
        registry.add("collector.datasource.username",database::getUsername);
        registry.add("collector.datasource.password",database::getPassword);
    }
    @AfterAll static void stop() {database.stop();}
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Test void independentCollectorBootstrapsAndAcceptsWithoutAnyProductDatasourceOrUsers() throws Exception {
        assertThat(context.getBeansOfType(DataSource.class)).containsOnlyKeys("collectorDataSource");
        assertThat(context.getBeansOfType(UserAccountService.class)).isEmpty();
        assertThat(context.getBeansOfType(CollectionRequestService.class)).isEmpty();
        assertThat(context.getBeansOfType(CollectorJobController.class)).hasSize(1);
        var job=new JobRequest(1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),TaskType.STATS,new RepositoryTarget("202","KHolodilin","repo-growth-monitor"),LocalDate.now(),1,Parameters.repository(),"rgm-results",Instant.now().plusSeconds(3600));
        String body=JsonMapper.builder().build().writeValueAsString(job);
        mvc.perform(post("/internal/v1/jobs").header("Authorization","Bearer "+TOKEN).header("Idempotency-Key",job.attemptId().toString()).contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.jobId").value(job.jobId().toString()));
        mvc.perform(post("/internal/v1/jobs").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me")).andExpect(status().isForbidden());
    }
}
