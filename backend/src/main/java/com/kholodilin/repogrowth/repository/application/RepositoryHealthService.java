package com.kholodilin.repogrowth.repository.application;

import com.kholodilin.repogrowth.repository.api.HealthCheckItem;
import com.kholodilin.repogrowth.repository.api.RepositoryHealthResponse;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.domain.RepositoryHealthFacts;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchQueryJdbcRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RepositoryHealthService {

    private final RepositoryJdbcRepository repositoryJdbcRepository;
    private final SearchQueryJdbcRepository searchQueryJdbcRepository;
    private final RepositoryHealthEvaluator evaluator;

    public RepositoryHealthService(
            RepositoryJdbcRepository repositoryJdbcRepository,
            SearchQueryJdbcRepository searchQueryJdbcRepository,
            RepositoryHealthEvaluator evaluator
    ) {
        this.repositoryJdbcRepository = repositoryJdbcRepository;
        this.searchQueryJdbcRepository = searchQueryJdbcRepository;
        this.evaluator = evaluator;
    }

    public RepositoryHealthResponse forRepository(Repository repository) {
        RepositoryHealthFacts facts = repositoryJdbcRepository.findHealth(repository.id())
                .orElse(RepositoryHealthFacts.empty());
        return evaluator.evaluate(
                repository,
                repositoryJdbcRepository.findTopics(repository.id()),
                searchQueryJdbcRepository.countByRepository(repository.id()),
                facts
        );
    }

    /** Passed Discoverability checks, or null before the first stats collection stored health facts. */
    public Integer discoverabilityPassed(Repository repository) {
        return discoverabilityScore(repository).passed();
    }

    public DiscoverabilityScore discoverabilityScore(Repository repository) {
        if (repositoryJdbcRepository.findHealth(repository.id()).isEmpty()) {
            return new DiscoverabilityScore(null, null);
        }
        List<HealthCheckItem> items = forRepository(repository).discoverability();
        int passed = 0;
        for (HealthCheckItem item : items) {
            if (item.passed()) {
                passed++;
            }
        }
        return new DiscoverabilityScore(passed, items.size());
    }

    public record DiscoverabilityScore(Integer passed, Integer total) {
    }
}
