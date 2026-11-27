package com.kholodilin.repogrowth.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.auth.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.catalog.model.ActivityStatus;
import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.OwnerType;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.repository.OwnerDirectoryReadModel;
import com.kholodilin.repogrowth.catalog.repository.OwnerDirectoryReadModel.OwnerRow;
import com.kholodilin.repogrowth.catalog.service.ActivityClassifier;
import com.kholodilin.repogrowth.catalog.service.RepositoryHealthService;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogControllerTest {

  @Mock ResourceAccessPolicy access;
  @Mock OwnerDirectoryReadModel owners;
  @Mock RepositoryService repositories;
  @Mock RepositoryHealthService health;
  @Mock ActivityClassifier activity;
  @Mock PublicationService publication;

  CatalogController controller;
  Repository repository;
  GitHubOwner owner;

  @BeforeEach
  void setUp() {
    controller = new CatalogController(access, owners, repositories, health, activity, publication);
    Instant now = Instant.parse("2026-11-01T00:00:00Z");
    owner =
        new GitHubOwner(1L, 1L, "acme", OwnerType.USER, null, "https://github.com/acme", now, now);
    repository =
        new Repository(
            13L,
            99L,
            1L,
            "outbox",
            "acme/outbox",
            "desc",
            "PUBLIC",
            "main",
            "Java",
            false,
            false,
            21,
            0,
            16,
            1,
            7,
            true,
            now,
            now,
            now,
            now,
            null,
            null,
            now,
            now);
  }

  @Test
  void ownerAndRepositoryCardsRequireVisibility() {
    when(owners.list(null)).thenReturn(List.of(new OwnerRow(1L, "acme", List.of(13L))));
    when(access.filterReadable(List.of(13L), PublicationService.Module.DASHBOARD))
        .thenReturn(List.of(13L));
    when(repositories.owner(1L)).thenReturn(owner);
    assertThat(controller.owner("1").login()).isEqualTo("acme");

    doNothing().when(access).requireReadable(13L, PublicationService.Module.DASHBOARD);
    when(repositories.get(13L)).thenReturn(repository);
    when(activity.classify(false, repository.activityAt())).thenReturn(ActivityStatus.ACTIVE);
    when(publication.allows(13L, PublicationService.Module.DASHBOARD)).thenReturn(true);
    when(publication.allows(13L, PublicationService.Module.TRAFFIC)).thenReturn(true);
    when(publication.allows(13L, PublicationService.Module.REFERRERS)).thenReturn(false);
    when(publication.allows(13L, PublicationService.Module.PATHS)).thenReturn(false);
    when(publication.allows(13L, PublicationService.Module.TOPICS)).thenReturn(true);
    assertThat(controller.repository("13").modules().topics()).isTrue();
    assertThat(controller.repository("13").modules().referrers()).isFalse();
  }

  @Test
  void ownerRepositoriesSupportExactNameAndCursorPaging() {
    when(owners.list(null)).thenReturn(List.of(new OwnerRow(1L, "acme", List.of(13L, 14L))));
    when(access.filterReadable(List.of(13L, 14L), PublicationService.Module.DASHBOARD))
        .thenReturn(List.of(13L, 14L));
    Instant now = Instant.parse("2026-11-01T00:00:00Z");
    Repository kafka =
        new Repository(
            14L,
            100L,
            1L,
            "kafka",
            "acme/kafka",
            "d",
            "PUBLIC",
            "main",
            "Java",
            false,
            false,
            10,
            0,
            5,
            1,
            3,
            true,
            now,
            now,
            now,
            now,
            null,
            null,
            now,
            now);
    when(repositories.list(false)).thenReturn(List.of(repository, kafka));
    when(activity.classify(false, repository.activityAt())).thenReturn(ActivityStatus.ACTIVE);
    when(activity.classify(false, kafka.activityAt())).thenReturn(ActivityStatus.ACTIVE);
    when(publication.allows(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(true);

    var named = controller.ownerRepositories("1", "kafka", null, 20);
    assertThat(named.items()).extracting(c -> c.name()).containsExactly("kafka");

    var page = controller.ownerRepositories("1", null, "13", 1);
    assertThat(page.items()).extracting(c -> c.repositoryId()).containsExactly("14");
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void healthAndValidationPaths() {
    doNothing().when(access).requireReadable(13L, PublicationService.Module.DASHBOARD);
    when(repositories.get(13L)).thenReturn(repository);
    when(health.forRepository(repository))
        .thenReturn(new RepositoryHealthResponse(List.of(), List.of()));
    assertThat(controller.repositoryHealth("13").repositoryId()).isEqualTo("13");

    assertThatThrownBy(() -> controller.owner("0")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> controller.owner("abc")).isInstanceOf(ApiException.class);
    when(owners.list(null)).thenReturn(List.of());
    assertThatThrownBy(() -> controller.owner("1")).isInstanceOf(ApiException.class);
  }

  @Test
  void skipsHiddenRepositoriesAndSurfacesNextCursor() {
    when(owners.list(null)).thenReturn(List.of(new OwnerRow(1L, "acme", List.of(13L, 14L))));
    when(access.filterReadable(List.of(13L, 14L), PublicationService.Module.DASHBOARD))
        .thenReturn(List.of(13L));
    Instant now = Instant.parse("2026-11-01T00:00:00Z");
    Repository kafka =
        new Repository(
            14L,
            100L,
            1L,
            "kafka",
            "acme/kafka",
            "d",
            "PUBLIC",
            "main",
            "Java",
            false,
            false,
            10,
            0,
            5,
            1,
            3,
            true,
            now,
            now,
            now,
            now,
            null,
            null,
            now,
            now);
    when(repositories.list(false)).thenReturn(List.of(repository, kafka));
    when(activity.classify(false, repository.activityAt())).thenReturn(ActivityStatus.ACTIVE);
    when(publication.allows(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(true);
    var page = controller.ownerRepositories("1", null, null, 20);
    assertThat(page.items()).extracting(c -> c.repositoryId()).containsExactly("13");

    when(access.filterReadable(List.of(13L, 14L), PublicationService.Module.DASHBOARD))
        .thenReturn(List.of(13L, 14L));
    when(activity.classify(false, kafka.activityAt())).thenReturn(ActivityStatus.ACTIVE);
    var paged = controller.ownerRepositories("1", null, null, 1);
    assertThat(paged.items()).hasSize(1);
    assertThat(paged.nextCursor()).isEqualTo("14");

    when(access.filterReadable(List.of(13L), PublicationService.Module.DASHBOARD))
        .thenReturn(List.of());
    when(owners.list(null)).thenReturn(List.of(new OwnerRow(1L, "acme", List.of(13L))));
    assertThatThrownBy(() -> controller.owner("1")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> controller.ownerRepositories("1", null, "-1", 20))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> controller.ownerRepositories("1", null, "bad", 20))
        .isInstanceOf(ApiException.class);
  }
}
