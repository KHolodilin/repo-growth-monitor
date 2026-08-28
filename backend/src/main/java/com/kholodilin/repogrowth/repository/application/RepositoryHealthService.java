package com.kholodilin.repogrowth.repository.application;

import com.kholodilin.repogrowth.repository.api.RepositoryHealthResponse;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.domain.RepositoryHealthFacts;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchQueryJdbcRepository;
import org.springframework.stereotype.Service;

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
}
