package com.kholodilin.repogrowth.credentials.service;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.repogrowth.common.api.*;
import com.kholodilin.repogrowth.common.api.ErrorCode;
import com.kholodilin.repogrowth.credentials.configuration.CredentialVaultProperties;
import com.kholodilin.repogrowth.credentials.model.CredentialMetadata;
import com.kholodilin.repogrowth.credentials.repository.CredentialJdbcRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "rgm.credentials.enabled", havingValue = "true")
public class CredentialVault {
  /** @deprecated Prefer {@link CredentialMetadata}. */
  public record Metadata(
      String ref, long version, boolean revoked, Instant expiresAt, String quotaIdentity) {
    public static Metadata from(CredentialMetadata metadata) {
      return new Metadata(
          metadata.ref(),
          metadata.version(),
          metadata.revoked(),
          metadata.expiresAt(),
          metadata.quotaIdentity());
    }
  }

  private final CredentialJdbcRepository credentials;
  private final TransactionTemplate tx;
  private final CredentialEncryption encryption;
  private final CredentialVaultProperties properties;
  private final Clock clock;

  public CredentialVault(
      CredentialJdbcRepository credentials,
      PlatformTransactionManager tm,
      CredentialVaultProperties properties,
      Clock clock) {
    this.credentials = credentials;
    this.tx = new TransactionTemplate(tm);
    this.properties = properties;
    this.clock = clock;
    encryption = new CredentialEncryption(properties);
  }

  private ApiException denied() {
    return new ApiException(
        ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "Credential assignment is not authorized");
  }

  public Metadata rotate(String ref, String token, Instant expiresAt) {
    return rotate(ref, token, expiresAt, null);
  }

  public Metadata rotate(String ref, String token, Instant expiresAt, String quotaIdentity) {
    if (quotaIdentity != null && !quotaIdentity.matches("[A-Za-z0-9_.:-]{1,128}"))
      throw ApiException.validation("Invalid quota identity");
    if (ref == null
        || !ref.matches("[A-Za-z0-9_-]{1,128}")
        || token == null
        || token.isBlank()
        || token.length() > 4096
        || token.chars().anyMatch(c -> c <= 32 || c == 127))
      throw ApiException.validation("Invalid credential input");
    final Instant storedExpiry =
        expiresAt == null ? null : expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (storedExpiry != null && !storedExpiry.isAfter(clock.instant()))
      throw ApiException.validation("Token expiry must be in the future");
    return tx.execute(status -> {
      credentials.ensureCredentialRow(ref);
      var row = credentials.lockCredentialByRef(ref);
      long id = ((Number) row.get("id")).longValue(),
          version = ((Number) row.get("current_version")).longValue() + 1;
      var encrypted = encryption.encrypt(ref, version, token);
      credentials.insertCredentialVersion(id, version, encrypted, storedExpiry);
      credentials.activateCredentialVersion(id, version);
      String identity = quotaIdentity == null ? (String) row.get("quota_identity") : quotaIdentity;
      credentials.updateQuotaIdentity(id, identity);
      return new Metadata(ref, version, false, storedExpiry, identity);
    });
  }

  public AuthorizationScope bind(long repositoryId, String ref) {
    return tx.execute(status -> {
      if (!credentials.repositoryExistsForUpdate(repositoryId))
        throw ApiException.notFound("Repository not found");
      long credentialId =
          credentials
              .activeCredentialIdByRef(ref)
              .orElseThrow(() -> ApiException.notFound("Active credential not found"));
      UUID context = UUID.randomUUID();
      credentials.upsertRepositoryBinding(repositoryId, credentialId, context);
      return new AuthorizationScope(context.toString(), ref);
    });
  }

  public boolean revoke(String ref) {
    return credentials.revokeByRef(ref);
  }

  @org.springframework.beans.factory.annotation.Value("${rgm.credentials.default-ref:}")
  private String defaultRef = "";

  public AuthorizationScope scope(long repositoryId) {
    return tx.execute(status -> {
      // Serialize first-use binding; an explicit/revoked assignment must never be replaced by a
      // default.
      if (!defaultRef.isBlank()) {
        credentials.lockRepositoryForUpdate(repositoryId);
        if (credentials.repositoryBindingCount(repositoryId) == 0L) bind(repositoryId, defaultRef);
      }
      var rows = credentials.activeRepositoryBinding(repositoryId);
      if (rows.isEmpty())
        throw new ApiException(
            ErrorCode.INTERNAL_ERROR,
            HttpStatus.SERVICE_UNAVAILABLE,
            "Repository credential is not configured");
      return new AuthorizationScope(
          rows.getFirst().get("authorization_context").toString(),
          (String) rows.getFirst().get("ref"));
    });
  }

  /**
   * Authorization for credential-scoped discovery when no repository binding exists yet. Reuses one
   * durable context per credential so empty-install refresh stays coalescable.
   */
  public AuthorizationScope discoveryScope(String ref) {
    return tx.execute(status -> {
      long credentialId =
          credentials
              .lockActiveCredentialIdByRef(ref)
              .orElseThrow(() -> ApiException.notFound("Active credential not found"));
      var existing = credentials.discoveryScopes(credentialId);
      UUID context;
      if (existing.isEmpty()) {
        context = UUID.randomUUID();
        credentials.insertDiscoveryScope(credentialId, context);
      } else {
        context = (UUID) existing.getFirst().get("authorization_context");
      }
      return new AuthorizationScope(context.toString(), ref);
    });
  }

  public CredentialLease resolve(String node, CredentialRequest request) {
    return tx.execute(status -> {
      credentials.shareLockCollectionRequestForTask(request.jobId().toString());
      credentials.shareLockCollectionTask(request.jobId().toString());
      var rows =
          credentials.resolveLeaseRows(
              request.jobId().toString(), request.attemptId().toString(), node);
      if (rows.isEmpty()) throw denied();
      var row = rows.getFirst();
      Instant now = clock.instant();
      Instant deadline = ((Timestamp) row.get("deadline_at")).toInstant();
      Instant expiry =
          row.get("expires_at") == null ? null : ((Timestamp) row.get("expires_at")).toInstant();
      Instant until = now.plus(properties.cacheLease());
      if (until.isAfter(deadline)) until = deadline;
      if (expiry != null && until.isAfter(expiry)) until = expiry;
      if (!until.isAfter(now)) throw denied();
      String ref = (String) row.get("ref");
      long version = ((Number) row.get("current_version")).longValue();
      String token =
          encryption.decrypt(
              ref,
              version,
              new CredentialEncryption.Encrypted(
                  (String) row.get("key_id"),
                  (byte[]) row.get("nonce"),
                  (byte[]) row.get("ciphertext")));
      return new CredentialLease(
          1,
          row.get("authorization_context").toString(),
          ref,
          Long.toString(version),
          token,
          expiry,
          until,
          (String) row.get("quota_identity"));
    });
  }
}
