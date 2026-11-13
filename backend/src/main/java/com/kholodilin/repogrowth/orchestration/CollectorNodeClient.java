package com.kholodilin.repogrowth.orchestration;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
/** Configured node endpoints only; all calls run outside product transactions. */
public final class CollectorNodeClient {
    private final OrchestrationProperties properties;private final RestClient client;private final JsonMapper json;
    public CollectorNodeClient(OrchestrationProperties properties,JsonMapper json){
        this.properties=properties;this.json=json;var factory=new SimpleClientHttpRequestFactory(){@Override protected void prepareConnection(java.net.HttpURLConnection c,String method)throws IOException{super.prepareConnection(c,method);c.setInstanceFollowRedirects(false);}};
        factory.setConnectTimeout(properties.connectTimeout());factory.setReadTimeout(properties.readTimeout());client=RestClient.builder().requestFactory(factory).build();
    }
    private <T>Optional<T> get(String node,String endpoint,String path,Class<T> type){
        var configured=properties.trusted(node,endpoint);if(configured.serviceToken()==null || configured.serviceToken().isBlank())throw new IllegalStateException("Node authentication unavailable");
        try{return client.get().uri(endpoint+path).headers(h->h.setBearerAuth(configured.serviceToken())).exchange((request,response)->{
            if(response.getStatusCode().value()==404)return Optional.empty();if(response.getStatusCode().value()!=200)throw new IllegalStateException("Node unavailable");
            byte[] bytes=response.getBody().readNBytes(5*1024*1024+1);if(bytes.length>5*1024*1024)throw new IllegalStateException("Node payload exceeds bounds");return Optional.of(json.readValue(bytes,type));
        });}catch(RuntimeException error){throw new IllegalStateException("Node status unavailable");}
    }
    public boolean ready(String node,String endpoint){var status=get(node,endpoint,"/internal/v1/status",JsonNode.class);return status.isPresent() && status.get().path("schemaVersion").asInt()==1 && status.get().path("accepting").asBoolean(false) && status.get().path("executionEnabled").asBoolean(false);}
    public Optional<JobStatus> status(String node,String endpoint,UUID collectorJobId,UUID attemptId){return get(node,endpoint,collectorJobId==null?"/internal/v1/jobs/by-attempt/"+attemptId:"/internal/v1/jobs/"+collectorJobId,JobStatus.class);}
    public Optional<ResultDelivery> result(String node,String endpoint,UUID id){return get(node,endpoint,"/internal/v1/jobs/"+id+"/result",ResultDelivery.class);}
    public void confirm(String node,String endpoint,UUID id,UUID eventId){
        var configured=properties.trusted(node,endpoint);
        try{client.post().uri(endpoint+"/internal/v1/jobs/"+id+"/delivery/ack").headers(h->h.setBearerAuth(configured.serviceToken())).contentType(MediaType.APPLICATION_JSON).body(Map.of("eventId",eventId)).exchange((request,response)->{if(!response.getStatusCode().is2xxSuccessful() && response.getStatusCode().value()!=404)throw new IllegalStateException("Node confirmation unavailable");return null;});}
        catch(RuntimeException error){throw new IllegalStateException("Node confirmation unavailable");}
    }
}
