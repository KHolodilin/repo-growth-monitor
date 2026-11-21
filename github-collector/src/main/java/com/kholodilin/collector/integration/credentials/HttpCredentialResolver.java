package com.kholodilin.collector.integration.credentials;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.integration.HttpClients;
import com.kholodilin.collector.model.CredentialSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.client.RestClient;

/**
 * Only configured HTTPS authorities are used. Response/exception bodies must not escape this
 * boundary.
 */
@RequiredArgsConstructor
public final class HttpCredentialResolver implements CredentialResolver {
  private final CredentialSettings settings;
  private final RestClient client;

  public static RestClient restClient(CredentialSettings settings) {
    return HttpClients.noRedirect(settings.connectTimeout(), settings.readTimeout());
  }

  @Override
  public CredentialLease resolve(String origin, CredentialRequest request) {
    var authority =
        settings.origins().stream()
            .filter(o -> o.origin().equals(origin))
            .findFirst()
            .orElseThrow(CredentialUnavailableException::new);
    try {
      return client
          .post()
          .uri(
              authority.endpoint().toString().replaceAll("/+$", "")
                  + "/internal/v1/credentials/resolve")
          .headers(h -> h.setBearerAuth(authority.serviceToken()))
          .contentType(MediaType.APPLICATION_JSON)
          .body(request)
          .exchange((sent, response) -> {
            if (response.getStatusCode().value() != HttpStatus.OK.value())
              throw new CredentialUnavailableException();
            var lease = response.bodyTo(CredentialLease.class);
            if (lease == null) throw new CredentialUnavailableException();
            return lease;
          });
    } catch (RuntimeException error) {
      throw new CredentialUnavailableException();
    }
  }
}
