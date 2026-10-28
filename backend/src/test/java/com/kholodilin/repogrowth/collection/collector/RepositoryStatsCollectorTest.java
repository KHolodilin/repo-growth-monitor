package com.kholodilin.repogrowth.collection.collector;

import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubCommunityProfileResponse;
import com.kholodilin.repogrowth.github.model.GitHubLicenseResponse;
import com.kholodilin.repogrowth.github.model.GitHubOwnerResponse;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.github.model.GitHubSocialPreview;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.domain.RepositoryHealthFacts;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.topic.application.TopicWatchSync;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryStatsCollectorTest {

    @Mock
    GitHubClient gitHubClient;
    @Mock
    RepositoryJdbcRepository repositoryJdbcRepository;
    @Mock
    TrafficJdbcRepository trafficJdbcRepository;
    @Mock
    TopicWatchSync topicWatchSync;
    @Mock
    TransactionTemplate transactionTemplate;

    private RepositoryStatsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new RepositoryStatsCollector(
                gitHubClient, repositoryJdbcRepository, trafficJdbcRepository, topicWatchSync, transactionTemplate
        );
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void collectStoresACustomSocialPreview() {
        when(repositoryJdbcRepository.findHealth(13L)).thenReturn(Optional.empty());
        when(gitHubClient.getRepository("acme", "demo")).thenReturn(remote());
        when(gitHubClient.countContributors("acme", "demo")).thenReturn(4);
        when(gitHubClient.listContributors("acme", "demo")).thenReturn(List.of(
                new com.kholodilin.repogrowth.github.model.GitHubContributorItem("alice", "User", null),
                new com.kholodilin.repogrowth.github.model.GitHubContributorItem(null, "Anonymous", null),
                new com.kholodilin.repogrowth.github.model.GitHubContributorItem("bob", "User", null)
        ));
        when(gitHubClient.latestCommitAt("acme", "demo")).thenReturn(Optional.of(Instant.parse("2026-09-01T00:00:00Z")));
        when(gitHubClient.getCommunityProfile("acme", "demo")).thenReturn(GitHubCommunityProfileResponse.empty());
        when(gitHubClient.getReadme("acme", "demo")).thenReturn(Optional.of("# demo\n"));
        when(gitHubClient.fileExists(anyString(), anyString(), anyString())).thenReturn(true);
        when(gitHubClient.hasIssueTemplates("acme", "demo")).thenReturn(true);
        when(gitHubClient.latestReleaseAt("acme", "demo")).thenReturn(Optional.of(Instant.parse("2026-08-01T00:00:00Z")));
        when(gitHubClient.getSocialPreview("acme", "demo")).thenReturn(new GitHubSocialPreview(true, "https://img/a.png"));

        collector.collect(context());

        ArgumentCaptor<RepositoryHealthFacts> facts = ArgumentCaptor.forClass(RepositoryHealthFacts.class);
        verify(repositoryJdbcRepository).upsertHealth(anyLong(), facts.capture());
        assertThat(facts.getValue().hasSocialPreview()).isTrue();
        verify(repositoryJdbcRepository).updateLastReleaseAt(anyLong(), any());
        verify(repositoryJdbcRepository).updateStats(
                org.mockito.ArgumentMatchers.eq(13L),
                org.mockito.ArgumentMatchers.eq("demo"),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                any(),
                any(),
                any(),
                any(),
                org.mockito.ArgumentMatchers.anyBoolean()
        );
        verify(trafficJdbcRepository).replaceContributorLogins(
                org.mockito.ArgumentMatchers.eq(13L),
                org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 9, 23)),
                org.mockito.ArgumentMatchers.eq(List.of("alice", "bob"))
        );
        verify(topicWatchSync).sync(13L);
    }

    @Test
    void collectClearsAnEmptyAboutAndSkipsLoginsWhenTheListFails() {
        when(repositoryJdbcRepository.findHealth(13L)).thenReturn(Optional.empty());
        when(gitHubClient.getRepository("acme", "demo")).thenReturn(remoteWithoutDescription());
        when(gitHubClient.countContributors("acme", "demo")).thenReturn(1);
        when(gitHubClient.listContributors(anyString(), anyString())).thenThrow(new IllegalStateException("list"));
        when(gitHubClient.latestCommitAt("acme", "demo")).thenReturn(Optional.empty());
        when(gitHubClient.getCommunityProfile("acme", "demo")).thenReturn(GitHubCommunityProfileResponse.empty());
        when(gitHubClient.getReadme("acme", "demo")).thenReturn(Optional.empty());
        when(gitHubClient.fileExists(anyString(), anyString(), anyString())).thenReturn(false);
        when(gitHubClient.hasIssueTemplates("acme", "demo")).thenReturn(false);
        when(gitHubClient.latestReleaseAt("acme", "demo")).thenReturn(Optional.empty());
        when(gitHubClient.getSocialPreview("acme", "demo")).thenReturn(new GitHubSocialPreview(false, null));

        collector.collect(context());

        verify(repositoryJdbcRepository).updateStats(
                org.mockito.ArgumentMatchers.eq(13L),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                any(),
                any(),
                any(),
                any(),
                org.mockito.ArgumentMatchers.anyBoolean()
        );
        verify(trafficJdbcRepository, never()).replaceContributorLogins(anyLong(), any(), any());
    }

    @Test
    void collectClearsBlankAboutAndDropsBlankContributorLogins() {
        when(repositoryJdbcRepository.findHealth(13L)).thenReturn(Optional.empty());
        when(gitHubClient.getRepository("acme", "demo")).thenReturn(remoteWithBlankDescription());
        when(gitHubClient.countContributors("acme", "demo")).thenReturn(1);
        when(gitHubClient.listContributors("acme", "demo")).thenReturn(List.of(
                new com.kholodilin.repogrowth.github.model.GitHubContributorItem("  ", "User", null),
                new com.kholodilin.repogrowth.github.model.GitHubContributorItem("zoe", "User", null)
        ));
        when(gitHubClient.latestCommitAt("acme", "demo")).thenReturn(Optional.empty());
        when(gitHubClient.getCommunityProfile("acme", "demo")).thenReturn(GitHubCommunityProfileResponse.empty());
        when(gitHubClient.getReadme("acme", "demo")).thenReturn(Optional.empty());
        when(gitHubClient.fileExists(anyString(), anyString(), anyString())).thenReturn(false);
        when(gitHubClient.hasIssueTemplates("acme", "demo")).thenReturn(false);
        when(gitHubClient.latestReleaseAt("acme", "demo")).thenReturn(Optional.empty());
        when(gitHubClient.getSocialPreview("acme", "demo")).thenReturn(new GitHubSocialPreview(false, null));

        collector.collect(context());

        verify(repositoryJdbcRepository).updateStats(
                org.mockito.ArgumentMatchers.eq(13L),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                any(),
                any(),
                any(),
                any(),
                org.mockito.ArgumentMatchers.anyBoolean()
        );
        verify(trafficJdbcRepository).replaceContributorLogins(
                org.mockito.ArgumentMatchers.eq(13L),
                org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 9, 23)),
                org.mockito.ArgumentMatchers.eq(List.of("zoe"))
        );
    }

    @Test
    void collectKeepsThePreviousPreviewWhenGitHubFails() {
        when(repositoryJdbcRepository.findHealth(13L)).thenReturn(Optional.of(new RepositoryHealthFacts(
                "https://example.com", true, true, true, true, true, true, true, true, true, true
        )));
        when(gitHubClient.getRepository("acme", "demo")).thenReturn(remote());
        when(gitHubClient.countContributors(anyString(), anyString())).thenThrow(new IllegalStateException("contributors"));
        when(gitHubClient.listContributors(anyString(), anyString())).thenThrow(new IllegalStateException("list"));
        when(gitHubClient.latestCommitAt(anyString(), anyString())).thenThrow(new IllegalStateException("commit"));
        when(gitHubClient.getCommunityProfile(anyString(), anyString())).thenThrow(new IllegalStateException("profile"));
        when(gitHubClient.getReadme(anyString(), anyString())).thenThrow(new IllegalStateException("readme"));
        when(gitHubClient.fileExists(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("file"));
        when(gitHubClient.latestReleaseAt(anyString(), anyString())).thenThrow(new IllegalStateException("release"));
        when(gitHubClient.getSocialPreview(anyString(), anyString())).thenThrow(new IllegalStateException("preview"));

        collector.collect(context());

        ArgumentCaptor<RepositoryHealthFacts> facts = ArgumentCaptor.forClass(RepositoryHealthFacts.class);
        verify(repositoryJdbcRepository).upsertHealth(anyLong(), facts.capture());
        assertThat(facts.getValue().hasSocialPreview()).isTrue();
        verify(repositoryJdbcRepository, never()).updateLastReleaseAt(anyLong(), any());
    }

    private CollectionContext context() {
        CollectionJob job = new CollectionJob(
                1L, 2L, 13L, CollectionJobType.REPOSITORY_STATS, LocalDate.of(2026, 9, 23),
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

    private GitHubRepositoryResponse remote() {
        return new GitHubRepositoryResponse(
                200L, "demo", "acme/demo", "demo", false, "public", "main", "Java",
                false, false, 11, 1, 2, 0, "https://github.com/acme/demo",
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"),
                new GitHubOwnerResponse(1L, "acme", "User", null, null),
                List.of("kafka"), " https://example.com ", new GitHubLicenseResponse("mit", "MIT", "MIT")
        );
    }

    private GitHubRepositoryResponse remoteWithoutDescription() {
        return new GitHubRepositoryResponse(
                200L, "demo", "acme/demo", null, false, "public", "main", "Java",
                false, false, 11, 1, 2, 0, "https://github.com/acme/demo",
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"),
                new GitHubOwnerResponse(1L, "acme", "User", null, null),
                List.of(), null, null
        );
    }

    private GitHubRepositoryResponse remoteWithBlankDescription() {
        return new GitHubRepositoryResponse(
                200L, "demo", "acme/demo", "   ", false, "public", "main", "Java",
                false, false, 11, 1, 2, 0, "https://github.com/acme/demo",
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"),
                new GitHubOwnerResponse(1L, "acme", "User", null, null),
                List.of(), null, null
        );
    }
}
