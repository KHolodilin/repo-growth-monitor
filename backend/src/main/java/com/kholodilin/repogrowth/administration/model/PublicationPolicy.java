package com.kholodilin.repogrowth.administration.model;

/** Desired publication flags for a repository. */
public record PublicationPolicy(
    boolean dashboard, boolean traffic, boolean referrers, boolean paths, boolean topics) {}
