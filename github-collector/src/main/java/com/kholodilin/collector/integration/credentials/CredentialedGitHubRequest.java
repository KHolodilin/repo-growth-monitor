package com.kholodilin.collector.integration.credentials;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Execution primitive for #215. A real limiter gate must be supplied by #214; there is no bypass
 * bean.
 */
@RequiredArgsConstructor
public final class CredentialedGitHubRequest {
  public record QuotaKey(String origin, String credentialRef, String quotaIdentity) {
    public QuotaKey(String origin, String credentialRef) {
      this(origin, credentialRef, "github:unclassified");
    }
  }

  public interface Permit extends AutoCloseable {
    @Override
    void close();
  }

  @FunctionalInterface
  public interface RequestGate {
    Permit acquire(QuotaKey key);
  }

  @FunctionalInterface
  public interface Call<T> {
    T execute(String token);
  }

  public static final class Failure extends RuntimeException {
    private final int status;
    private final java.time.Instant retryAt;

    public Failure(int status) {
      this(status, null);
    }

    public Failure(int status, java.time.Instant retryAt) {
      super("GitHub request failed (HTTP " + status + ")");
      this.status = status;
      this.retryAt = retryAt;
    }

    public java.time.Instant retryAt() {
      return retryAt;
    }

    public int status() {
      return status;
    }

    /**
     * Maps the transport-level outcome to the semantic error the service layer decides on. HTTP
     * status codes never leave the integration layer.
     */
    public GitHubSourceException toSemantic() {
      if ((status == HttpStatus.TOO_MANY_REQUESTS.value() || status == HttpStatus.FORBIDDEN.value())
          && retryAt != null) return new GitHubRateLimitedException(retryAt);
      HttpStatus resolved = HttpStatus.resolve(status);
      if (resolved == HttpStatus.CONTENT_TOO_LARGE) return new GitHubResponseTooLargeException();
      if (resolved == HttpStatus.UNAUTHORIZED) return new GitHubAuthenticationFailedException();
      if (resolved == HttpStatus.NOT_FOUND) return new GitHubNotFoundException();
      if (resolved == HttpStatus.FORBIDDEN) return new GitHubPermissionDeniedException();
      return new GitHubSourceUnavailableException();
    }
  }

  private final CredentialCache cache;
  private final RequestGate gate;

  /**
   * Runs {@code call}; transport failures surface only as semantic {@link GitHubSourceException}.
   */
  public <T> T execute(String origin, JobRequest job, Call<T> call) {
    try {
      return attempt(origin, job, call);
    } catch (Failure failure) {
      throw failure.toSemantic();
    }
  }

  private <T> T attempt(String origin, JobRequest job, Call<T> call) {
    CredentialLease lease = cache.get(origin, job);
    try {
      return invoke(origin, lease, call);
    } catch (Failure error) {
      if (error.status() != HttpStatus.UNAUTHORIZED.value()) throw error;
      lease = cache.refreshRejected(origin, job, lease);
      try {
        return invoke(origin, lease, call);
      } catch (Failure second) {
        if (second.status() == HttpStatus.UNAUTHORIZED.value()) cache.reject(origin, lease);
        throw second;
      }
    }
  }

  private <T> T invoke(String origin, CredentialLease lease, Call<T> call) {
    try (Permit _ =
        gate.acquire(new QuotaKey(origin, lease.credentialRef(), lease.quotaIdentity()))) {
      return call.execute(lease.token());
    } catch (Failure error) {
      throw error;
    } catch (RuntimeException error) {
      throw new Failure(0);
    }
  }
}
