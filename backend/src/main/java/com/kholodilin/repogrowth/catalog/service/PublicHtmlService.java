package com.kholodilin.repogrowth.catalog.service;

import com.kholodilin.repogrowth.administration.configuration.PublicationProperties;
import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.repository.PublicHtmlJdbcRepository;
import com.kholodilin.repogrowth.common.api.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HTML pretty-path gate for {@code /owner} and {@code /owner/repo}. Not a JSON read API — resource
 * GETs live under {@code /api/v1/owners} and {@code /api/v1/repositories/{id}}.
 */
@Service
@Transactional(readOnly = true)
public class PublicHtmlService {

  private final PublicationService policy;
  private final PublicationProperties properties;
  private final PublicHtmlJdbcRepository reads;

  public PublicHtmlService(
      PublicationService policy, PublicationProperties properties, PublicHtmlJdbcRepository reads) {
    this.policy = policy;
    this.properties = properties;
    this.reads = reads;
  }

  public PublicationService.Resolved resolve(String path) {
    return policy.resolve(path);
  }

  public List<Long> scope(String owner, String repository, PublicationService.Module module) {
    var resolved = policy.resolve("/" + owner + (repository == null ? "" : "/" + repository));
    String flag =
        switch (module) {
          case DASHBOARD -> "public_dashboard_enabled";
          case TRAFFIC -> "public_traffic_enabled";
          case REFERRERS -> "public_referrers_enabled";
          case PATHS -> "public_paths_enabled";
          case TOPICS -> "public_topics_enabled";
        };
    var ids =
        reads.scopedRepositoryIds(
            resolved.ownerId(), resolved.repositoryId(), flag, ageMillis());
    if (ids.isEmpty()) {
      throw missing();
    }
    return ids;
  }

  public void validateDefinition(String owner, String repository, boolean topics, long id) {
    long parent = parent(topics, id);
    if (!scope(owner, repository, PublicationService.Module.DASHBOARD).contains(parent)) {
      throw missing();
    }
  }

  public long validateTopic(String owner, String repository, String topic) {
    if (topic == null || topic.isBlank() || topic.length() > 200) {
      throw missing();
    }
    var ids = scope(owner, repository, PublicationService.Module.TOPICS);
    var rows = reads.topicWatchByTopic(ids, topic);
    if (rows.isEmpty()) {
      throw missing();
    }
    return ((Number) rows.getFirst().get("id")).longValue();
  }

  private long parent(boolean topics, long id) {
    var rows = reads.parentDefinitionOrRun(topics, id, false);
    if (rows.isEmpty()) {
      throw missing();
    }
    long repository = ((Number) rows.getFirst().get("repository_id")).longValue();
    if (topics
        ? !policy.allows(repository, PublicationService.Module.TOPICS)
        : !policy.queryEligible(((Number) rows.getFirst().get("definition")).longValue())) {
      throw missing();
    }
    return repository;
  }

  private long ageMillis() {
    return properties.verificationMaxAge().toMillis();
  }

  private static ApiException missing() {
    return ApiException.notFound("Page not found");
  }
}
