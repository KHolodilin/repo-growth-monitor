package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubWatchersMappingTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void subscribersCountIsUsedForWatchersNotWatchersCount() throws Exception {
        GitHubRepositoryResponse response = mapper.readValue("""
                {
                  "id": 1002,
                  "name": "kafka-starter",
                  "full_name": "acme/kafka-starter",
                  "stargazers_count": 41,
                  "watchers_count": 41,
                  "subscribers_count": 12,
                  "forks_count": 6,
                  "open_issues_count": 2,
                  "owner": { "id": 42, "login": "acme", "type": "User" }
                }
                """, GitHubRepositoryResponse.class);
        assertThat(response.stargazersCount()).isEqualTo(41);
        assertThat(response.watchers()).isEqualTo(12);
        assertThat(response.watchers()).isNotEqualTo(response.stargazersCount());
    }

    @Test
    void missingSubscribersCountDefaultsToZero() throws Exception {
        GitHubRepositoryResponse response = mapper.readValue("""
                {
                  "id": 1,
                  "name": "a",
                  "full_name": "acme/a",
                  "stargazers_count": 1,
                  "watchers_count": 1,
                  "forks_count": 0,
                  "open_issues_count": 0,
                  "owner": { "id": 10, "login": "acme", "type": "User" }
                }
                """, GitHubRepositoryResponse.class);
        assertThat(response.watchers()).isZero();
    }
}
