package com.kholodilin.collector.contracts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static com.kholodilin.collector.contracts.CollectorProtocol.*;
import static org.assertj.core.api.Assertions.*;

class CollectorProtocolTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final Instant at = Instant.parse("2026-11-09T00:00:00Z");
    private JobRequest request(int version, TaskType type, Parameters parameters) {
        return new JobRequest(version, id, id, id, type, new RepositoryTarget("9007199254740993", "Owner", "repo"),
            LocalDate.parse("2026-11-09"), 1, parameters, "rgm-primary", at.plusSeconds(3600));
    }
    @Test void requestsRoundTripWithStringGithubIdentityAndNoCredentials() {
        for (TaskType type : TaskType.values()) {
            var value = request(1, type, new Parameters("language:Java", "java", "Java", 50, "stars", "desc"));
            var json = mapper.writeValueAsString(value);
            assertThat(mapper.readValue(json, JobRequest.class)).isEqualTo(value);
            assertThat(json).contains("9007199254740993").doesNotContain("credential", "token", "callbackUrl");
        }
    }
    @Test void rejectsUnsupportedVersionAndMissingTaskInputs() {
        assertThatThrownBy(() -> request(2, TaskType.STATS, Parameters.repository())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(1, TaskType.SEARCH_QUERY, Parameters.repository())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(1, TaskType.TOPIC_WATCH, Parameters.repository())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Parameters(null,null,null,101,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Parameters(null,null,null,10,null,"random")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RepositoryTarget("0", "owner", "repo")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RepositoryTarget("1", " ", "repo")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void acceptedStatusAndCredentialLeaseRoundTripWithoutLoggingSecrets() {
        var accepted = new JobAccepted(1,id,id,id,JobState.ACCEPTED,at);
        assertThat(mapper.readValue(mapper.writeValueAsString(accepted), JobAccepted.class)).isEqualTo(accepted);
        assertThatThrownBy(() -> new JobAccepted(1,id,id,id,JobState.RUNNING,at)).isInstanceOf(IllegalArgumentException.class);
        var lease = new CredentialLease(1,"scope","ref","3","secret-test",at.plusSeconds(7200),at.plusSeconds(3600));
        assertThat(lease.toString()).doesNotContain("secret-test");
        assertThat(mapper.readValue(mapper.writeValueAsString(lease), CredentialLease.class)).isEqualTo(lease);
        assertThatThrownBy(() -> new CredentialLease(1,"scope","ref","3","token",at,at.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        var resolve = new CredentialRequest(1,id,id);
        assertThat(mapper.readValue(mapper.writeValueAsString(resolve), CredentialRequest.class)).isEqualTo(resolve);
    }
    @Test void resultRoundTripPreservesMissingObservationsAndDefensiveCopies() {
        var traffic = new ArrayList<TrafficDay>(); traffic.add(new TrafficDay(LocalDate.parse("2026-11-08"),0L,null,1L,1L));
        var snapshot = new Snapshot(at,false,Map.of(Metric.STARS,12L),null,traffic,
            List.of(new SourceObservation("/issues", "Issues",3,2)),
            List.of(new RankedRepository("12","other/repo",1,5,2)),8L,null,Map.of("description","demo"),
            List.of(new EventObservation("event1","RELEASE",at,"Release",null,null)));
        traffic.clear(); assertThat(snapshot.traffic()).hasSize(1);
        assertThat(snapshot.contributorLogins()).isNull(); assertThat(snapshot.traffic().getFirst().visitors()).isNull();
        var result = new ResultDelivery(1,id,id,id,id,id,TaskType.STATS,"12",JobState.SUCCEEDED,snapshot,null);
        assertThat(mapper.readValue(mapper.writeValueAsString(result),ResultDelivery.class)).isEqualTo(result);
        var failure = new Failure(ErrorCode.RATE_LIMITED,true,"Rate limited",at);
        var failed = new ResultDelivery(1,id,id,id,id,id,TaskType.STATS,"12",JobState.FAILED,null,failure);
        assertThat(mapper.readValue(mapper.writeValueAsString(failed),ResultDelivery.class)).isEqualTo(failed);
        assertThatThrownBy(() -> new ResultDelivery(1,id,id,id,id,id,TaskType.STATS,"12",JobState.RUNNING,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResultDelivery(1,id,id,id,id,id,TaskType.STATS,"12",JobState.SUCCEEDED,snapshot,failure)).isInstanceOf(IllegalArgumentException.class);
        var status = new JobStatus(1,id,id,id,JobState.RETRY,at,null,failure);
        assertThat(mapper.readValue(mapper.writeValueAsString(status),JobStatus.class)).isEqualTo(status);
    }
}
