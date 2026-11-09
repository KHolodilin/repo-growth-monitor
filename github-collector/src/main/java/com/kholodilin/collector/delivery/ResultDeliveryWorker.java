package com.kholodilin.collector.delivery;
import com.kholodilin.collector.credentials.CollectorCredentialProperties;
import com.kholodilin.collector.queue.JobQueue;
import java.io.IOException;
import java.time.*;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Only terminal immutable results are delivered; retrying transport never calls a source collector. */
public final class ResultDeliveryWorker {
    private final JobQueue queue;private final DeliveryProperties properties;private final CollectorCredentialProperties credentials;private final RestClient client;
    public ResultDeliveryWorker(JobQueue queue,DeliveryProperties properties,CollectorCredentialProperties credentials){
        this.queue=queue;this.properties=properties;this.credentials=credentials;
        if(properties.lease().compareTo(credentials.connectTimeout().plus(credentials.readTimeout()).plusSeconds(5))<=0)throw new IllegalArgumentException("Delivery lease must exceed callback timeouts");
        var factory=new SimpleClientHttpRequestFactory(){@Override protected void prepareConnection(java.net.HttpURLConnection c,String method)throws IOException{super.prepareConnection(c,method);c.setInstanceFollowRedirects(false);}};
        factory.setConnectTimeout(credentials.connectTimeout());factory.setReadTimeout(credentials.readTimeout());client=RestClient.builder().requestFactory(factory).build();
    }
    public boolean poll(String owner){
        var candidate=queue.claimDelivery(owner,properties.lease());if(candidate.isEmpty())return false;var delivery=candidate.get();
        var destination=properties.destinations().stream().filter(d->d.origin().equals(delivery.origin()) && d.ref().equals(delivery.destination())).findFirst();
        var authority=credentials.origins().stream().filter(o->o.origin().equals(delivery.origin())).findFirst();
        if(destination.isEmpty() || authority.isEmpty()){queue.deadLetter(delivery,"DESTINATION_UNAVAILABLE");return true;}
        try{
            int response=client.post().uri(destination.get().endpoint()).headers(h->h.setBearerAuth(authority.get().serviceToken())).contentType(MediaType.APPLICATION_JSON).body(delivery.payload()).exchange((request,result)->result.getStatusCode().value());
            if(response==204){if(queue.acknowledge(delivery))queue.archive(delivery.jobId(),delivery.jobGeneration());}
            else if(response==408 || response==429 || response>=500)retry(delivery);
            else queue.deadLetter(delivery,"REJECTED");
        }catch(RuntimeException error){retry(delivery);}
        return true;
    }
    private void retry(JobQueue.Delivery delivery){if(delivery.generation()>=properties.maximumAttempts())queue.deadLetter(delivery,"RETRY_EXHAUSTED");else queue.retryDelivery(delivery,Instant.now().plusMillis(Math.min(300000,properties.retryDelay().toMillis()*(1L<<Math.min(6,delivery.generation()-1)))));}
}
