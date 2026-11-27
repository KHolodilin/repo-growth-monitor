package com.kholodilin.repogrowth.event.domain;

/** Count of promotion growth events for a repository in a window. */
public record PromotionCount(long repositoryId, String fullName, int count) {}
