package com.kholodilin.repogrowth.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.api.ApiSurface;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResourceAccessPolicyTest {

  @Mock CurrentUserAccess currentUserAccess;
  @Mock PublicationService publicationService;
  @InjectMocks ResourceAccessPolicy policy;

  @Test
  void adminMayReadAnyRepository() {
    when(currentUserAccess.canSeePrivateRepositories()).thenReturn(true);
    assertThat(policy.canReadRepository(13L, PublicationService.Module.DASHBOARD))
        .isEqualTo(ResourceAccessPolicy.Decision.ALLOW);
  }

  @Test
  void anonymousSeesOnlyPublishedRepositories() {
    when(currentUserAccess.canSeePrivateRepositories()).thenReturn(false);
    when(publicationService.allows(13L, PublicationService.Module.DASHBOARD)).thenReturn(true);
    when(publicationService.allows(99L, PublicationService.Module.DASHBOARD)).thenReturn(false);
    assertThat(policy.canReadRepository(13L, PublicationService.Module.DASHBOARD))
        .isEqualTo(ResourceAccessPolicy.Decision.ALLOW);
    assertThat(policy.canReadRepository(99L, PublicationService.Module.DASHBOARD))
        .isEqualTo(ResourceAccessPolicy.Decision.NOT_FOUND);
  }

  @Test
  void mutateRequiresAdminAndMapsAuthFailures() {
    when(currentUserAccess.canSeePrivateRepositories()).thenReturn(false);
    when(currentUserAccess.authenticated()).thenReturn(false);
    assertThat(
            policy.can(
                13L,
                ResourceAccessPolicy.ResourceAction.MUTATE,
                PublicationService.Module.DASHBOARD))
        .isEqualTo(ResourceAccessPolicy.Decision.UNAUTHENTICATED);
    when(currentUserAccess.authenticated()).thenReturn(true);
    assertThat(
            policy.can(
                13L,
                ResourceAccessPolicy.ResourceAction.MUTATE,
                PublicationService.Module.DASHBOARD))
        .isEqualTo(ResourceAccessPolicy.Decision.FORBIDDEN);
    when(currentUserAccess.canSeePrivateRepositories()).thenReturn(true);
    assertThat(
            policy.can(
                13L,
                ResourceAccessPolicy.ResourceAction.MUTATE,
                PublicationService.Module.DASHBOARD))
        .isEqualTo(ResourceAccessPolicy.Decision.ALLOW);
  }

  @Test
  void requireReadableAndRequireActionThrowMappedApiExceptions() {
    when(currentUserAccess.canSeePrivateRepositories()).thenReturn(false);
    when(publicationService.allows(13L, PublicationService.Module.TRAFFIC)).thenReturn(false);
    assertThatThrownBy(() -> policy.requireReadable(13L, PublicationService.Module.TRAFFIC))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Repository not found");

    when(currentUserAccess.authenticated()).thenReturn(false);
    assertThatThrownBy(() ->
            policy.requireAction(
                13L, ResourceAccessPolicy.ResourceAction.MUTATE, PublicationService.Module.TRAFFIC))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Authentication required");

    when(currentUserAccess.authenticated()).thenReturn(true);
    assertThatThrownBy(() ->
            policy.requireAction(
                13L, ResourceAccessPolicy.ResourceAction.MUTATE, PublicationService.Module.TRAFFIC))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Forbidden");

    when(currentUserAccess.canSeePrivateRepositories()).thenReturn(true);
    policy.requireReadable(13L, PublicationService.Module.TRAFFIC);
    policy.requireAction(
        13L, ResourceAccessPolicy.ResourceAction.MUTATE, PublicationService.Module.TRAFFIC);
  }

  @Test
  void filterReadableDropsHiddenIdsBeforePagination() {
    when(currentUserAccess.canSeePrivateRepositories()).thenReturn(false);
    when(publicationService.allows(13L, PublicationService.Module.DASHBOARD)).thenReturn(true);
    when(publicationService.allows(99L, PublicationService.Module.DASHBOARD)).thenReturn(false);
    assertThat(
            policy.filterReadable(
                java.util.Arrays.asList(13L, 99L, null), PublicationService.Module.DASHBOARD))
        .containsExactly(13L);
    assertThat(policy.filterReadable(List.of(), PublicationService.Module.DASHBOARD)).isEmpty();
    when(currentUserAccess.canSeePrivateRepositories()).thenReturn(true);
    assertThat(policy.filterReadable(List.of(13L, 99L), PublicationService.Module.DASHBOARD))
        .containsExactly(13L, 99L);
  }

  @Test
  void opsSurfaceIsSeparatedFromResourceReads() {
    assertThat(policy.isOpsSurface("/api/v1/admin/collectors")).isTrue();
    assertThat(policy.isOpsSurface("/api/v1/admin/github-budget")).isTrue();
    assertThat(policy.isOpsSurface("/api/v1/admin/system")).isTrue();
    assertThat(policy.isOpsSurface("/api/v1/admin/credentials")).isTrue();
    assertThat(policy.isOpsSurface("/api/v1/dashboard")).isFalse();
    assertThat(policy.isOpsSurface("/api/v1/admin/system/status")).isTrue();
    assertThat(policy.isOpsSurface("/admin")).isTrue();
    assertThat(policy.isOpsSurface("/internal/v1/collectors")).isTrue();
    assertThat(policy.surfaceOf(null)).isEqualTo(ApiSurface.RESOURCE);
    assertThat(policy.isOpsSurface("/api/v1/admin/repositories/13/traffic")).isFalse();
    assertThat(policy.isOpsSurface("/api/v1/public/owners/acme")).isFalse();
  }
}
