package com.kholodilin.repogrowth.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.catalog.repository.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.config.CollectorSearchProperties;
import com.kholodilin.repogrowth.event.persistence.GrowthEventSettingJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.topic.application.TopicWatchSync;
import com.kholodilin.repogrowth.topic.persistence.TopicWatchJdbcRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class CollectionRequestServiceRefreshTest {

  @Test
  void refreshWithoutAVaultNeverBuildsAnUnauthorizedDiscoverJob() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(any(String.class), eq(Long.class))).thenReturn(List.of());
    CollectionRequestService service =
        new CollectionRequestService(
            jdbc,
            mock(PlatformTransactionManager.class),
            mock(OrchestrationProperties.class),
            mock(RepositoryJdbcRepository.class),
            mock(SearchQueryJdbcRepository.class),
            mock(TopicWatchJdbcRepository.class),
            mock(JsonMapper.class),
            mock(TopicWatchSync.class),
            mock(GrowthEventSettingJdbcRepository.class),
            new CollectorSearchProperties(null, null));

    assertThatThrownBy(() -> service.refresh(LocalDate.of(2026, 11, 22)))
        .isInstanceOf(ApiException.class)
        .hasMessage("Configure a credential before refreshing repositories");
  }
}
