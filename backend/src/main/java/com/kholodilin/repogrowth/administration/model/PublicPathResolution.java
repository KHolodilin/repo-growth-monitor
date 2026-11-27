package com.kholodilin.repogrowth.administration.model;

/**
 * Resolved public HTML path: owner and optional repository, with canonical path and redirect hint.
 */
public record PublicPathResolution(
    long ownerId, Long repositoryId, String canonicalPath, boolean redirect) {}
