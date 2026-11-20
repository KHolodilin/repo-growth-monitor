package com.kholodilin.collector.integration;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Shared outbound HTTP client policy: bounded timeouts, and never follow redirects. */
public final class HttpClients {
  private HttpClients() {}

  public static RestClient noRedirect(Duration connectTimeout, Duration readTimeout) {
    var factory =
        new SimpleClientHttpRequestFactory() {
          @Override
          protected void prepareConnection(HttpURLConnection connection, String method)
              throws IOException {
            super.prepareConnection(connection, method);
            connection.setInstanceFollowRedirects(false);
          }
        };
    factory.setConnectTimeout(connectTimeout);
    factory.setReadTimeout(readTimeout);
    return RestClient.builder().requestFactory(factory).build();
  }
}
