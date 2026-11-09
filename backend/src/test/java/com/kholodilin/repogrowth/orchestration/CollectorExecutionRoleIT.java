package com.kholodilin.repogrowth.orchestration;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.queue.JobQueue;
import com.kholodilin.collector.execution.AssignedJobExecutor;
import com.kholodilin.collector.delivery.ResultDeliveryWorker;
import java.time.*;
import java.util.*;
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
@SpringBootTest(properties={"spring.task.scheduling.enabled=false","collector.execution.enabled=true","collector.delivery.enabled=true","collector.delivery.allow-http=true","collector.credentials.enabled=true","collector.credentials.allow-http=true",
 "collector.credentials.connect-timeout=100ms","collector.credentials.read-timeout=100ms",
 "collector.credentials.origins[0].origin=rgm","collector.credentials.origins[0].endpoint=http://127.0.0.1:1","collector.credentials.origins[0].service-token=node-service-secret-0123456789012345",
 "collector.http.clients[0].origin=rgm","collector.http.clients[0].token=collector-service-secret-0123456789012345",
 "collector.delivery.destinations[0].origin=rgm","collector.delivery.destinations[0].ref=results","collector.delivery.destinations[0].endpoint=http://127.0.0.1:1/internal/v1/collector-results"})
@ActiveProfiles({"collector","collector-http"}) @AutoConfigureMockMvc
class CollectorExecutionRoleIT {
    static PostgreSQLContainer database=new PostgreSQLContainer("postgres:18.6");static{database.start();}
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){properties.add("collector.datasource.url",database::getJdbcUrl);properties.add("collector.datasource.username",database::getUsername);properties.add("collector.datasource.password",database::getPassword);}
    @Autowired ApplicationContext context;@Autowired JobQueue queue;@Autowired MockMvc mvc;@Autowired JsonMapper json;
    @Test void standaloneCollectorExecutesAndKeepsUndeliveredResultsWithoutAnyProductDatasource()throws Exception{
        assertThat(context.getBean(AssignedJobExecutor.class)).isNotNull();assertThat(context.getBean(ResultDeliveryWorker.class)).isNotNull();assertThat(context.containsBean("dataSource")).isFalse();
        var job=new JobRequest(1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),TaskType.STATS,new RepositoryTarget("202","owner","repo"),LocalDate.now(),1,Parameters.repository(),"results",Instant.now().minusSeconds(1),new AuthorizationScope("ctx","main"));
        var accepted=queue.accept(new JobQueue.Submission("rgm",job.jobId(),job.attemptId(),json.writeValueAsString(job),"results",job.deadlineAt()));
        long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();while(queue.result(accepted.publicId(),"rgm").isEmpty() && System.nanoTime()<deadline)Thread.sleep(50);
        var result=json.readValue(queue.result(accepted.publicId(),"rgm").orElseThrow(),ResultDelivery.class);assertThat(result.failure().code()).isEqualTo(ErrorCode.DEADLINE_EXCEEDED);assertThat(result.attemptId()).isEqualTo(job.attemptId());
        mvc.perform(get("/internal/v1/status").header("Authorization","Bearer collector-service-secret-0123456789012345")).andExpect(status().isOk()).andExpect(jsonPath("$.executionEnabled").value(true));
        mvc.perform(get("/internal/v1/jobs/"+accepted.publicId()+"/result").header("Authorization","Bearer collector-service-secret-0123456789012345")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/internal/v1/jobs/"+accepted.publicId()+"/result")).andExpect(status().isUnauthorized());
    }
}
