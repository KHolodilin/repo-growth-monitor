package com.kholodilin.repogrowth.catalog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.model.ActivityStatus;
import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.OwnerType;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.model.RepositorySignals;
import com.kholodilin.repogrowth.catalog.service.ActivityClassifier;
import com.kholodilin.repogrowth.catalog.service.OwnerDirectoryService;
import com.kholodilin.repogrowth.catalog.service.OwnerDirectoryService.OwnerRow;
import com.kholodilin.repogrowth.catalog.service.RepositoryHealthResponse;
import com.kholodilin.repogrowth.catalog.service.RepositoryHealthService;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.GlobalExceptionHandler;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class CatalogControllerTest {

  ResourceAccessPolicy access;
  OwnerDirectoryService owners;
  RepositoryService repositories;
  RepositoryHealthService health;
  ActivityClassifier activity;
  PublicationService publication;
  MockMvc mockMvc;

  Repository repository;
  Repository kafka;
  GitHubOwner owner;

  @BeforeEach
  void setUp() {
    access = mock(ResourceAccessPolicy.class);
    owners = mock(OwnerDirectoryService.class);
    repositories = mock(RepositoryService.class);
    health = mock(RepositoryHealthService.class);
    activity = mock(ActivityClassifier.class);
    publication = mock(PublicationService.class);
    mockMvc =
        standaloneSetup(
                new CatalogController(access, owners, repositories, health, activity, publication))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
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
    kafka =
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
  }

  @Test
  void ownerAndRepositoryCardsRequireVisibility() throws Exception {
    when(owners.list(null)).thenReturn(List.of(new OwnerRow(1L, "acme", List.of(13L))));
    when(access.filterReadable(eq(List.of(13L)), eq(PublicationService.Module.DASHBOARD)))
        .thenReturn(List.of(13L));
    when(repositories.owner(1L)).thenReturn(owner);

    mockMvc
        .perform(get("/api/v1/owners/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.login").value("acme"))
        .andExpect(jsonPath("$.ownerType").value("USER"));

    doNothing().when(access).requireReadable(13L, PublicationService.Module.DASHBOARD);
    when(repositories.get(13L)).thenReturn(repository);
    when(repositories.owner(1L)).thenReturn(owner);
    when(repositories.signalsFor(List.of(13L)))
        .thenReturn(Map.of(13L, new RepositorySignals(true, false)));
    when(repositories.topics(13L)).thenReturn(List.of("java", "outbox"));
    when(health.forRepository(repository))
        .thenReturn(new RepositoryHealthResponse(List.of(), List.of()));
    when(activity.classify(false, repository.activityAt())).thenReturn(ActivityStatus.ACTIVE);
    when(publication.allows(13L, PublicationService.Module.DASHBOARD)).thenReturn(true);
    when(publication.allows(13L, PublicationService.Module.TRAFFIC)).thenReturn(true);
    when(publication.allows(13L, PublicationService.Module.REFERRERS)).thenReturn(false);
    when(publication.allows(13L, PublicationService.Module.PATHS)).thenReturn(false);
    when(publication.allows(13L, PublicationService.Module.TOPICS)).thenReturn(true);

    mockMvc
        .perform(get("/api/v1/repositories/13"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(13))
        .andExpect(jsonPath("$.githubId").value(99))
        .andExpect(jsonPath("$.fullName").value("acme/outbox"))
        .andExpect(jsonPath("$.stars").value(21))
        .andExpect(jsonPath("$.trackingEnabled").value(true))
        .andExpect(jsonPath("$.accessible").value(true))
        .andExpect(jsonPath("$.trafficPermissionMissing").value(false))
        .andExpect(jsonPath("$.githubUrl").value("https://github.com/acme/outbox"))
        .andExpect(jsonPath("$.activityStatus").value("ACTIVE"))
        .andExpect(jsonPath("$.topics[0]").value("java"))
        .andExpect(jsonPath("$.owner.id").value(1))
        .andExpect(jsonPath("$.owner.login").value("acme"))
        .andExpect(jsonPath("$.modules.topics").value(true))
        .andExpect(jsonPath("$.modules.referrers").value(false));
  }

  @Test
  void ownerRepositoriesSupportExactNameAndCursorPaging() throws Exception {
    when(owners.list(null)).thenReturn(List.of(new OwnerRow(1L, "acme", List.of(13L, 14L))));
    when(access.filterReadable(eq(List.of(13L, 14L)), eq(PublicationService.Module.DASHBOARD)))
        .thenReturn(List.of(13L, 14L));
    when(repositories.list(false)).thenReturn(List.of(repository, kafka));
    when(activity.classify(false, repository.activityAt())).thenReturn(ActivityStatus.ACTIVE);
    when(activity.classify(false, kafka.activityAt())).thenReturn(ActivityStatus.ACTIVE);
    when(publication.allows(anyLong(), any(PublicationService.Module.class))).thenReturn(true);

    mockMvc
        .perform(get("/api/v1/owners/1/repositories").param("name", "kafka"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value("kafka"))
        .andExpect(jsonPath("$.items.length()").value(1));

    mockMvc
        .perform(get("/api/v1/owners/1/repositories").param("cursor", "13").param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].repositoryId").value("14"))
        .andExpect(jsonPath("$.nextCursor").isEmpty());
  }

  @Test
  void healthAndValidationPaths() throws Exception {
    when(owners.list(null)).thenReturn(List.of(new OwnerRow(1L, "acme", List.of(13L))));
    when(access.filterReadable(eq(List.of(13L)), eq(PublicationService.Module.DASHBOARD)))
        .thenReturn(List.of(13L));
    doNothing().when(access).requireReadable(13L, PublicationService.Module.DASHBOARD);
    when(repositories.get(13L)).thenReturn(repository);
    when(health.forRepository(repository))
        .thenReturn(new RepositoryHealthResponse(List.of(), List.of()));

    mockMvc
        .perform(get("/api/v1/repositories/13/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repositoryId").value("13"));

    mockMvc
        .perform(get("/api/v1/owners/0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mockMvc
        .perform(get("/api/v1/owners/abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    when(owners.list(null)).thenReturn(List.of());
    mockMvc
        .perform(get("/api/v1/owners/1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void skipsHiddenRepositoriesAndSurfacesNextCursor() throws Exception {
    when(owners.list(null)).thenReturn(List.of(new OwnerRow(1L, "acme", List.of(13L, 14L))));
    when(access.filterReadable(eq(List.of(13L, 14L)), eq(PublicationService.Module.DASHBOARD)))
        .thenReturn(List.of(13L));
    when(repositories.list(false)).thenReturn(List.of(repository, kafka));
    when(activity.classify(false, repository.activityAt())).thenReturn(ActivityStatus.ACTIVE);
    when(publication.allows(anyLong(), any(PublicationService.Module.class))).thenReturn(true);

    mockMvc
        .perform(get("/api/v1/owners/1/repositories"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].repositoryId").value("13"))
        .andExpect(jsonPath("$.items.length()").value(1));

    when(access.filterReadable(eq(List.of(13L, 14L)), eq(PublicationService.Module.DASHBOARD)))
        .thenReturn(List.of(13L, 14L));
    when(activity.classify(false, kafka.activityAt())).thenReturn(ActivityStatus.ACTIVE);
    mockMvc
        .perform(get("/api/v1/owners/1/repositories").param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.nextCursor").value("14"));

    when(access.filterReadable(eq(List.of(13L)), eq(PublicationService.Module.DASHBOARD)))
        .thenReturn(List.of());
    when(owners.list(null)).thenReturn(List.of(new OwnerRow(1L, "acme", List.of(13L))));
    mockMvc
        .perform(get("/api/v1/owners/1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    when(access.filterReadable(eq(List.of(13L, 14L)), eq(PublicationService.Module.DASHBOARD)))
        .thenReturn(List.of(13L, 14L));
    when(owners.list(null)).thenReturn(List.of(new OwnerRow(1L, "acme", List.of(13L, 14L))));
    mockMvc
        .perform(get("/api/v1/owners/1/repositories").param("cursor", "-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mockMvc
        .perform(get("/api/v1/owners/1/repositories").param("cursor", "bad"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
