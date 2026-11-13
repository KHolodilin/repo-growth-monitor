package com.kholodilin.collector.acceptance;

import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("collector.http")
public record CollectorHttpProperties(boolean enabled,List<Client> clients) {
    public CollectorHttpProperties {
        clients=clients==null?List.of():List.copyOf(clients);
        Set<String> origins=new HashSet<>(),tokens=new HashSet<>();
        for(Client client:clients) {
            if(client.origin()==null || !client.origin().matches("[a-zA-Z0-9_-]{1,64}") || client.token()==null || client.token().length()<32
                || !origins.add(client.origin()) || !tokens.add(client.token())) throw new IllegalArgumentException("Unique collector origins and service tokens of at least 32 characters are required");
        }
        if(enabled && clients.isEmpty()) throw new IllegalArgumentException("Collector HTTP requires at least one authenticated origin");
    }
    public record Client(String origin,String token) {
        @Override public String toString() {return "Client[origin="+origin+", token=REDACTED]";}
    }
}
