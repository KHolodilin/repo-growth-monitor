package com.kholodilin.repogrowth.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.repository.OwnerDirectoryReadModel;
import com.kholodilin.repogrowth.catalog.repository.OwnerDirectoryReadModel.OwnerRow;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OwnerDirectoryControllerTest {

  @Mock ResourceAccessPolicy access;
  @Mock OwnerDirectoryReadModel readModel;
  @InjectMocks OwnerDirectoryController controller;

  @Test
  void filtersHiddenOwnersBeforeCursorPaging() {
    when(readModel.list(null))
        .thenReturn(
            List.of(
                new OwnerRow(1L, "acme", List.of(13L)),
                new OwnerRow(2L, "hidden", List.of(99L)),
                new OwnerRow(3L, "beta", List.of(14L))));
    when(access.filterReadable(List.of(13L), PublicationService.Module.DASHBOARD))
        .thenReturn(List.of(13L));
    when(access.filterReadable(List.of(99L), PublicationService.Module.DASHBOARD))
        .thenReturn(List.of());
    when(access.filterReadable(List.of(14L), PublicationService.Module.DASHBOARD))
        .thenReturn(List.of(14L));

    var page = controller.owners(null, null, 1);
    assertThat(page.items())
        .extracting(OwnerDirectoryController.OwnerCard::login)
        .containsExactly("acme");
    assertThat(page.nextCursor()).isEqualTo("3");

    var next = controller.owners(null, "1", 10);
    assertThat(next.items())
        .extracting(OwnerDirectoryController.OwnerCard::login)
        .containsExactly("beta");
  }

  @Test
  void exactLoginLookupAndInvalidCursor() {
    when(readModel.list("acme")).thenReturn(List.of(new OwnerRow(1L, "acme", List.of(13L))));
    when(access.filterReadable(List.of(13L), PublicationService.Module.DASHBOARD))
        .thenReturn(List.of(13L));
    assertThat(controller.owners("acme", null, 20).items()).hasSize(1);
    assertThatThrownBy(() -> controller.owners(null, "bad", 20)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> controller.owners(null, "-1", 20)).isInstanceOf(ApiException.class);
  }
}
