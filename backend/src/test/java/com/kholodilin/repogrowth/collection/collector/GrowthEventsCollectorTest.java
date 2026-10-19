package com.kholodilin.repogrowth.collection.collector;

import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.common.api.ErrorCode;
import com.kholodilin.repogrowth.event.persistence.GrowthEventJdbcRepository;
import com.kholodilin.repogrowth.event.persistence.GrowthEventSettingJdbcRepository;
import com.kholodilin.repogrowth.event.persistence.GrowthEventStateJdbcRepository;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.github.model.GitHubSocialPreview;
import com.kholodilin.repogrowth.repository.domain.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GrowthEventsCollectorTest {

    @Mock
    GitHubClient gitHubClient;
    @Mock
    GrowthEventJdbcRepository eventRepository;
    @Mock
    GrowthEventSettingJdbcRepository settingRepository;
    @Mock
    GrowthEventStateJdbcRepository stateRepository;
    @Mock
    TransactionTemplate transactionTemplate;
    @Mock
    GitHubRepositoryResponse remote;

    private GrowthEventsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new GrowthEventsCollector(
                gitHubClient,
                eventRepository,
                settingRepository,
                stateRepository,
                transactionTemplate,
                Clock.systemUTC()
        );
    }

    @Test
    void namesTheGitHubCallThatFailed() {
        when(gitHubClient.getRepository("acme", "demo")).thenReturn(remote);
        when(gitHubClient.getReadmeDetails("acme", "demo")).thenReturn(Optional.empty());
        when(gitHubClient.getSocialPreview("acme", "demo")).thenReturn(new GitHubSocialPreview(false, null));
        when(gitHubClient.listIssues("acme", "demo")).thenThrow(GitHubException.validation("Validation Failed"));

        assertThatThrownBy(() -> collector.collect(context()))
                .isInstanceOf(GitHubException.class)
                .hasMessage("Could not load issues (Validation Failed)")
                .extracting(ex -> ((GitHubException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    private CollectionContext context() {
        CollectionJob job = new CollectionJob(
                1L, 2L, 13L, CollectionJobType.GROWTH_EVENTS, LocalDate.of(2026, 10, 19),
                CollectionJobStatus.RUNNING, 1, null, null, null, null, null, null, null
        );
        Repository repository = new Repository(
                13L, 200L, 1L, "demo", "acme/demo", "demo", "PUBLIC", "main", "Java",
                false, false, 1, 0, 0, 0, 0, true,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, null, null
        );
        return new CollectionContext(job, repository, "acme");
    }
}
