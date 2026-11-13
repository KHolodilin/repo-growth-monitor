package com.kholodilin.collector.credentials;
import com.sun.net.httpserver.HttpServer;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
class HttpCredentialResolverTest {
    @Test void sendsOnlyToTrustedAuthorityAndNeverFollowsRedirectsOrLeaksResponseBodies() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger leaks=new AtomicInteger();AtomicReference<String> auth=new AtomicReference<>();
        String serviceToken="separate-service-token-0123456789012";
        var lease=new CredentialLease(1,"context","ref","1","github-secret",null,Instant.now().plusSeconds(60));
        byte[] json=JsonMapper.builder().build().writeValueAsBytes(lease);
        server.createContext("/internal/v1/credentials/resolve",exchange->{auth.set(exchange.getRequestHeaders().getFirst("Authorization"));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,json.length);exchange.getResponseBody().write(json);exchange.close();});
        server.createContext("/redirect/internal/v1/credentials/resolve",exchange->{exchange.getResponseHeaders().set("Location","/leak");exchange.sendResponseHeaders(307,-1);exchange.close();});
        server.createContext("/leak",exchange->{leaks.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try {
            URI endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort());var request=new CredentialRequest(1,UUID.randomUUID(),UUID.randomUUID());
            var properties=new CollectorCredentialProperties(true,null,100,1000,null,null,null,true,List.of(new CollectorCredentialProperties.Origin("rgm",endpoint,serviceToken)));
            assertThat(new HttpCredentialResolver(properties).resolve("rgm",request)).isEqualTo(lease);assertThat(auth).hasValue("Bearer "+serviceToken);
            var redirect=new CollectorCredentialProperties(true,null,100,1000,null,null,null,true,List.of(new CollectorCredentialProperties.Origin("rgm",URI.create(endpoint+"/redirect"),serviceToken)));
            assertThatThrownBy(()->new HttpCredentialResolver(redirect).resolve("rgm",request)).isInstanceOf(CredentialUnavailableException.class).hasNoCause().hasMessageNotContaining(serviceToken);
            assertThat(leaks).hasValue(0);assertThatThrownBy(()->new HttpCredentialResolver(properties).resolve("untrusted",request)).isInstanceOf(CredentialUnavailableException.class);
        } finally {server.stop(0);}
    }
}
