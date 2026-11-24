package com.kholodilin.collector.model;

import java.util.List;

/** Authenticated service clients (origin + bearer token) allowed on the internal API. */
public record ServiceAuthSettings(List<Client> clients) {
  public ServiceAuthSettings {
    clients = clients == null ? List.of() : List.copyOf(clients);
  }

  /** One trusted caller. The token is never rendered. */
  public record Client(String origin, String token) {
    @Override
    public String toString() {
      return "Client[origin=" + origin + ", token=REDACTED]";
    }
  }
}
