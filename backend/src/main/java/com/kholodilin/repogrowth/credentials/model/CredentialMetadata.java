package com.kholodilin.repogrowth.credentials.model;

import java.time.Instant;

/** Stored credential tip metadata without the secret material. */
public record CredentialMetadata(
    String ref, long version, boolean revoked, Instant expiresAt, String quotaIdentity) {}
