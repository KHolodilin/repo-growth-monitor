package com.kholodilin.collector.credentials;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
/** Only configured HTTPS authorities are used. Response/exception bodies must not escape this boundary. */
public final class HttpCredentialResolver implements CredentialResolver {
    private final CollectorCredentialProperties properties;private final RestClient client;
    public HttpCredentialResolver(CollectorCredentialProperties properties) {
        this.properties=properties;
        var factory=new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection,String method) throws java.io.IOException {super.prepareConnection(connection,method);connection.setInstanceFollowRedirects(false);}
        };
        factory.setConnectTimeout(properties.connectTimeout());factory.setReadTimeout(properties.readTimeout());client=RestClient.builder().requestFactory(factory).build();
    }
    @Override public CredentialLease resolve(String origin,CredentialRequest request) {
        var authority=properties.origins().stream().filter(o->o.origin().equals(origin)).findFirst().orElseThrow(CredentialUnavailableException::new);
        try {
            return client.post().uri(authority.endpoint().toString().replaceAll("/+$","")+"/internal/v1/credentials/resolve")
                .headers(h->h.setBearerAuth(authority.serviceToken())).contentType(MediaType.APPLICATION_JSON).body(request)
                .exchange((sent,response)->{
                    if(response.getStatusCode().value()!=200) throw new CredentialUnavailableException();
                    var lease=response.bodyTo(CredentialLease.class);if(lease==null) throw new CredentialUnavailableException();return lease;
                });
        } catch(RuntimeException error) {throw new CredentialUnavailableException();}
    }
}
