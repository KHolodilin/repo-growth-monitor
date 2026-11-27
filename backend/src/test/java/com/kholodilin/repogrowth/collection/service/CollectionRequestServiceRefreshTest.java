package com.kholodilin.repogrowth.collection.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.catalog.repository.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.collection.configuration.OrchestrationProperties;
import com.kholodilin.repogrowth.collection.repository.CollectionRequestJdbcRepository;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.config.CollectorSearchProperties;
import com.kholodilin.repogrowth.events.repository.GrowthEventSettingJdbcRepository;
import com.kholodilin.repogrowth.search.repository.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.topics.repository.TopicWatchJdbcRepository;
import com.kholodilin.repogrowth.topics.service.TopicWatchSync;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class CollectionRequestServiceRefreshTest {

  @Test
  void refreshWithoutAVaultNeverBuildsAnUnauthorizedDiscoverJob() {
    CollectionRequestJdbcRepository requests = mock(CollectionRequestJdbcRepository.class);
    when(requests.discoverySeedRepositoryIds()).thenReturn(List.of());
    CollectionRequestService service =
        new CollectionRequestService(
            requests,
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
