package com.kholodilin.collector.execution;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.credentials.*;
import com.kholodilin.collector.queue.JobQueue;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
public final class GitHubSourceReader implements SourceReader {
    private final ObservationStore observations;private final CredentialCache cache;private final CollectorCredentialProperties credentials;
    private final ExecutionProperties properties;private final LocalGitHubPacer pacer;private final RestClient client;private final JsonMapper json=JsonMapper.builder().build();
    public GitHubSourceReader(ObservationStore observations,CredentialCache cache,CollectorCredentialProperties credentials,ExecutionProperties properties,LocalGitHubPacer pacer){
        this.observations=observations;this.cache=cache;this.credentials=credentials;this.properties=properties;this.pacer=pacer;
        var factory=new SimpleClientHttpRequestFactory(){@Override protected void prepareConnection(java.net.HttpURLConnection c,String method)throws IOException{super.prepareConnection(c,method);c.setInstanceFollowRedirects(false);}};
        factory.setConnectTimeout(properties.requestTimeout());factory.setReadTimeout(properties.requestTimeout());client=RestClient.builder().requestFactory(factory).build();
    }
    @Override public JsonNode read(JobQueue.Lease lease,JobRequest job,String key,String method,String path,String body,boolean optional){
        var stored=observations.find(lease,key);if(stored.isPresent())return json.readTree(stored.get());
        if(!job.deadlineAt().isAfter(java.time.Instant.now()))throw new CredentialedGitHubRequest.Failure(408);
        String resource=path.startsWith("/search/")?"search":path.equals("/graphql")?"graphql":"core";
        var limiter=new GitHubRequestLimiter(lease.origin(),job,resource,credentials,pacer,properties.requestTimeout().multipliedBy(2).plusSeconds(2));
        var requests=new CredentialedGitHubRequest(cache,limiter);
        JsonNode observation=requests.execute(lease.origin(),job,token->{
            var request=client.method(HttpMethod.valueOf(method)).uri(URI.create(properties.apiBaseUrl().toString().replaceAll("/+$","")+path))
                .headers(h->{h.setBearerAuth(token);h.set("Accept","application/vnd.github+json");h.set("X-GitHub-Api-Version","2022-11-28");}).contentType(MediaType.APPLICATION_JSON);
            if(body!=null)request.body(body);
            return request.exchange((sent,response)->{
                int status=response.getStatusCode().value();limiter.observe(optional && status==404?200:status,response.getHeaders());
                if(optional && status==404)return json.readTree("null");
                if(status<200 || status>=300){
                    var retry=retryAt(response.getHeaders());
                    if(status==403){String message=new String(response.getBody().readNBytes(4096),java.nio.charset.StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);if(retry==null && message.contains("rate limit"))retry=java.time.Instant.now().plusSeconds(60);}
                    if(optional && status==403 && retry==null)return json.readTree("null");
                    throw new CredentialedGitHubRequest.Failure(status,retry);
                }
                byte[] bytes=response.getBody().readNBytes(properties.maximumPayloadBytes()*4+1);
                if(bytes.length>properties.maximumPayloadBytes()*4)throw new CredentialedGitHubRequest.Failure(413);
                JsonNode parsed=bytes.length==0?json.readTree("null"):json.readTree(bytes);
                if(key.equals("contributor-count")){
                    long total=parsed.size();String link=response.getHeaders().getFirst("Link");
                    if(link!=null)for(String segment:link.split(","))if(segment.contains("rel=\"last\"")){
                        var matcher=java.util.regex.Pattern.compile("[?&]page=([0-9]+)").matcher(segment);if(matcher.find())total=Long.parseLong(matcher.group(1));
                    }
                    var count=json.createObjectNode();count.put("total",total);return count;
                }
                return project(key,parsed);
            });
        });
        observations.save(lease,key,json.writeValueAsString(observation));return observation;
    }
    private JsonNode project(String key,JsonNode raw){
        if(raw.isNull())return raw;
        if(key.equals("social-preview") && raw.hasNonNull("errors"))return json.readTree("null");
        if(key.startsWith("security:") || key.startsWith("issue-template:"))return json.createObjectNode().put("exists",true);
        java.util.List<String> fields=null;
        if(key.startsWith("issues:"))fields=java.util.List.of("id","number","title","state","html_url","created_at","user","labels","pull_request");
        else if(key.startsWith("pulls:"))fields=java.util.List.of("id","number","title","state","merged","html_url","created_at","merged_at","user");
        else if(key.startsWith("releases:") || key.equals("latest-release"))fields=java.util.List.of("id","draft","tag_name","name","html_url","published_at","created_at");
        else if(key.startsWith("contributors:"))fields=java.util.List.of("id","login","type","contributions");
        if(fields==null)return raw;
        if(raw.isArray()){var array=json.createArrayNode();for(var row:raw)array.add(fields(row,fields));return array;}
        return fields(raw,fields);
    }
    private JsonNode fields(JsonNode raw,java.util.List<String> fields){var projected=json.createObjectNode();for(String field:fields)if(raw.has(field))projected.set(field,raw.get(field));return projected;}
    @Override public java.time.Instant observedAt(JobQueue.Lease lease){return observations.firstObserved(lease);}
    private java.time.Instant retryAt(HttpHeaders headers){
        try{String after=headers.getFirst("Retry-After");if(after!=null)return java.time.Instant.now().plusSeconds(Long.parseLong(after));}catch(RuntimeException ignored){}
        try{String remaining=headers.getFirst("X-RateLimit-Remaining"),reset=headers.getFirst("X-RateLimit-Reset");if("0".equals(remaining) && reset!=null)return java.time.Instant.ofEpochSecond(Long.parseLong(reset));}catch(RuntimeException ignored){}
        return null;
    }
    @Override public void invalidate(JobQueue.Lease lease,String key){observations.remove(lease,key);}
}
