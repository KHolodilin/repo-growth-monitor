package com.kholodilin.repogrowth.credentials.model;

/** Whether a non-revoked vault credential exists and how far the collector path is ready. */
public record GitHubAccessStatus(boolean credentialsConfigured, TokenState tokenState) {}
