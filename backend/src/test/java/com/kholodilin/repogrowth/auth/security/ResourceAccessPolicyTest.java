package com.kholodilin.repogrowth.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.publication.PublicationService;
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
  void opsSurfaceIsSeparatedFromResourceReads() {
    assertThat(policy.isOpsSurface("/api/v1/admin/collectors")).isTrue();
    assertThat(policy.isOpsSurface("/api/v1/admin/github-budget")).isTrue();
    assertThat(policy.isOpsSurface("/api/v1/admin/repositories/13/traffic")).isFalse();
    assertThat(policy.isOpsSurface("/api/v1/public/owners/acme")).isFalse();
  }
}
