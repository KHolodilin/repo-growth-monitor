package com.kholodilin.repogrowth.github.configuration;

import com.kholodilin.repogrowth.common.config.GitHubProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class GitHubClientConfig {

    @Bean
    RestClient gitHubRestClient(GitHubProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(orDefault(properties.connectTimeout(), Duration.ofSeconds(10)))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(orDefault(properties.readTimeout(), Duration.ofSeconds(30)));

        RestClient.Builder githubBuilder = RestClient.builder()
                .baseUrl(properties.apiBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "repo-growth-monitor");

        if (properties.tokenConfigured()) {
            githubBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token());
        }
        return githubBuilder.build();
    }

    private Duration orDefault(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }
}
