package com.kholodilin.repogrowth.topics.model;

public record TopicResult(
    Long id,
    long topicRunId,
    int position,
    long githubRepositoryId,
    String fullName,
    String owner,
    int stars,
    int watchers,
    int forks,
    String language,
    String description,
    String htmlUrl) {}
