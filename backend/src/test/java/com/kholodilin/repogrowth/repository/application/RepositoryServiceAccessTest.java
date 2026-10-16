package com.kholodilin.repogrowth.repository.application;

import com.kholodilin.repogrowth.collection.planner.CollectionPlanner;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.config.GitHubProperties;
import com.kholodilin.repogrowth.common.observability.AppMetrics;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepositoryServiceAccessTest {

    @Test
    void skipsGitHubWhenTheTokenIsMissingOrRejected() {
        GitHubClient gitHubClient = mock(GitHubClient.class);
        GitHubProperties properties = mock(GitHubProperties.class);
        RepositoryJdbcRepository repositories = mock(RepositoryJdbcRepository.class);
        RepositoryService service = new RepositoryService(
                gitHubClient,
                properties,
                mock(GitHubOwnerJdbcRepository.class),
                repositories,
                mock(AppMetrics.class),
                mock(CollectionPlanner.class),
                mock(PlanningWindow.class)
        );
        when(properties.tokenConfigured()).thenReturn(false);

        assertThat(service.list(true)).isEmpty();
        verify(gitHubClient, never()).listAccessibleRepositories();
        verify(repositories, never()).findListed();

        when(properties.tokenConfigured()).thenReturn(true);
        when(gitHubClient.tokenRejected()).thenReturn(true);
        assertThatThrownBy(() -> service.list(true))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("rejected");
        verify(gitHubClient, never()).listAccessibleRepositories();

        when(repositories.signalsFor(List.of())).thenReturn(java.util.Map.of());
        assertThat(service.signalsFor(List.of())).isEmpty();
    }
}
