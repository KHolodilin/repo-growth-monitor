package com.kholodilin.repogrowth.orchestration;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
@ConditionalOnProperty(name="rgm.orchestration.enabled",havingValue="true")
public class CollectorTransport {
    private final OrchestrationProperties properties;
    private final RestClient client;
    public CollectorTransport(OrchestrationProperties properties) {
        this.properties=properties;
        var factory=new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection,String method) throws java.io.IOException {
                super.prepareConnection(connection,method); connection.setInstanceFollowRedirects(false);
            }
        };factory.setConnectTimeout(properties.connectTimeout());factory.setReadTimeout(properties.readTimeout());
        client=RestClient.builder().requestFactory(factory).build();
    }
    public JobAccepted send(DispatchRepository.Claim claim) {
        var node=properties.trusted(claim.nodeKey(),claim.endpoint());
        if(node.serviceToken()==null || node.serviceToken().isBlank()) throw new IllegalStateException("Collector service authentication is not configured");
        // No GitHub token here. A distinct service credential authenticates the selected collector.
        return client.post().uri(claim.endpoint()+"/internal/v1/jobs")
            .headers(h->h.setBearerAuth(node.serviceToken()))
            .header("Idempotency-Key",claim.request().attemptId().toString())
            .contentType(MediaType.APPLICATION_JSON).body(claim.request()).exchange((request,response)->{
                if(response.getStatusCode().value()!=201) {
                    if(response.getStatusCode().isError()) throw new HttpClientErrorException(response.getStatusCode());
                    throw new IllegalArgumentException("Expected durable 201 acceptance");
                }
                JobAccepted accepted=response.bodyTo(JobAccepted.class);
                if(accepted==null) throw new IllegalArgumentException("Missing durable acceptance"); return accepted;
            });
    }
}
