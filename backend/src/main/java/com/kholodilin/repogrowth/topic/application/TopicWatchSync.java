package com.kholodilin.repogrowth.topic.application;

import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.topic.domain.TopicWatch;
import com.kholodilin.repogrowth.topic.persistence.TopicWatchJdbcRepository;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class TopicWatchSync {

    private final RepositoryJdbcRepository repositoryJdbcRepository;
    private final TopicWatchJdbcRepository watchRepository;
    private final SearchProperties searchProperties;

    public TopicWatchSync(
            RepositoryJdbcRepository repositoryJdbcRepository,
            TopicWatchJdbcRepository watchRepository,
            SearchProperties searchProperties
    ) {
        this.repositoryJdbcRepository = repositoryJdbcRepository;
        this.watchRepository = watchRepository;
        this.searchProperties = searchProperties;
    }

    public void syncTracked() {
        for (Repository repository : repositoryJdbcRepository.findTracked()) {
            sync(repository);
        }
    }

    public List<TopicWatch> sync(long repositoryId) {
        return repositoryJdbcRepository.findById(repositoryId)
                .map(this::sync)
                .orElse(List.of());
    }

    public List<TopicWatch> sync(Repository repository) {
        List<String> topics = repositoryJdbcRepository.findTopics(repository.id());
        String language = blankToNull(repository.language());
        Set<String> desired = new HashSet<>();
        for (String topic : topics) {
            desired.add(key(topic, null));
            if (language != null) {
                desired.add(key(topic, language));
            }
        }
        List<TopicWatch> existing = watchRepository.findByRepository(repository.id());
        Set<String> seen = new HashSet<>();
        for (TopicWatch watch : existing) {
            String key = key(watch.topic(), watch.language());
            seen.add(key);
            boolean shouldEnable = desired.contains(key);
            if (watch.enabled() != shouldEnable) {
                watchRepository.setEnabled(watch.id(), shouldEnable);
            }
        }
        for (String topic : topics) {
            if (!seen.contains(key(topic, null))) {
                watchRepository.insert(repository.id(), topic, null, searchProperties.defaultResultLimit());
            }
            if (language != null && !seen.contains(key(topic, language))) {
                watchRepository.insert(repository.id(), topic, language, searchProperties.defaultResultLimit());
            }
        }
        return watchRepository.findByRepository(repository.id());
    }

    private static String key(String topic, String language) {
        String lang = language == null || language.isBlank() ? "" : language.trim().toLowerCase(Locale.ROOT);
        return topic.trim().toLowerCase(Locale.ROOT) + "\n" + lang;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
