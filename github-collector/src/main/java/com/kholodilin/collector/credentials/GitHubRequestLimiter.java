package com.kholodilin.collector.credentials;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Per-task adapter: local pacing plus one durable cross-node grant for every actual HTTP request. */
public final class GitHubRequestLimiter implements CredentialedGitHubRequest.RequestGate {
 public record Grant(UUID permitId,Instant leasedUntil,Instant retryAt) { }
 private final String origin,resource;
 private final JobRequest job;
 private final CollectorCredentialProperties.Origin authority;
 private final LocalGitHubPacer pacer;
 private final RestClient client;
 private final Duration requiredRequestTime;
 private final ThreadLocal<Active> active=new ThreadLocal<>();
 public GitHubRequestLimiter(String origin,JobRequest job,String resource,CollectorCredentialProperties properties,LocalGitHubPacer pacer) {
  this(origin,job,resource,properties,pacer,properties.connectTimeout().plus(properties.readTimeout()).plusSeconds(2));
 }
 public GitHubRequestLimiter(String origin,JobRequest job,String resource,CollectorCredentialProperties properties,LocalGitHubPacer pacer,Duration requiredRequestTime) {
  this.requiredRequestTime=requiredRequestTime;this.origin=origin;this.job=job;this.resource=resource;this.pacer=pacer;
  if(!Set.of("core","search","graphql").contains(resource))throw new IllegalArgumentException("Unsupported GitHub resource");
  authority=properties.origins().stream().filter(o->o.origin().equals(origin)).findFirst().orElseThrow(CredentialUnavailableException::new);
  var factory=new SimpleClientHttpRequestFactory(){
   @Override protected void prepareConnection(java.net.HttpURLConnection connection,String method) throws java.io.IOException {super.prepareConnection(connection,method);connection.setInstanceFollowRedirects(false);}
  };
  factory.setConnectTimeout(properties.connectTimeout());factory.setReadTimeout(properties.readTimeout());client=RestClient.builder().requestFactory(factory).build();
 }
 @Override public CredentialedGitHubRequest.Permit acquire(CredentialedGitHubRequest.QuotaKey key) {
  if(!origin.equals(key.origin()) || job.authorization()==null || !job.authorization().credentialRef().equals(key.credentialRef()))throw new CredentialUnavailableException();
  if(active.get()!=null)throw new IllegalStateException("Nested GitHub permits are forbidden");
  var local=pacer.tryAcquire(origin+":"+key.quotaIdentity(),resource).orElseThrow(()->new CredentialedGitHubRequest.Failure(429,Instant.now().plusSeconds(3)));
  try {
   Grant grant=post("acquire",Map.of("requestId",UUID.randomUUID(),"assignment",new CredentialRequest(1,job.jobId(),job.attemptId()),"resource",resource),Grant.class);
   if(grant==null || grant.permitId()==null || grant.leasedUntil()==null || !grant.leasedUntil().isAfter(Instant.now().plus(requiredRequestTime)))throw new CredentialedGitHubRequest.Failure(429,grant==null?Instant.now().plusSeconds(30):grant.retryAt());
   Active permit=new Active(grant.permitId(),local);active.set(permit);return permit;
  } catch(RuntimeException error) {local.close();if(error instanceof CredentialedGitHubRequest.Failure failure)throw failure;throw new CredentialedGitHubRequest.Failure(503,Instant.now().plusSeconds(30));}
 }
 /** Called by the GitHub transport before its response body is decoded. Headers/body never enter exceptions. */
 public void observe(int status,HttpHeaders headers) {
  Active permit=active.get();if(permit==null)throw new IllegalStateException("A GitHub response requires an active shared permit");
  permit.status=status;
  String observed=headers.getFirst("X-RateLimit-Resource");
  if(observed==null || observed.equals(resource)) {
   permit.remaining=nonnegative(headers.getFirst("X-RateLimit-Remaining"));
   Long reset=nonnegative(headers.getFirst("X-RateLimit-Reset"));
   if(reset!=null)try{permit.reset=Instant.ofEpochSecond(reset);}catch(RuntimeException ignored){}
  }
  String retry=headers.getFirst("Retry-After");
  if(retry!=null) {
   try {long seconds=Long.parseLong(retry);if(seconds>=0)permit.retry=Instant.now().plusSeconds(seconds);}
   catch(RuntimeException numeric) {try{permit.retry=ZonedDateTime.parse(retry,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}catch(RuntimeException ignored){}}
  }
  if(permit.remaining!=null && permit.remaining==0 && permit.reset!=null && (permit.retry==null || permit.reset.isAfter(permit.retry)))permit.retry=permit.reset;
 }
 private static Long nonnegative(String value){try{long parsed=Long.parseLong(value);return parsed<0?null:parsed;}catch(RuntimeException ignored){return null;}}
 private <T> T post(String action,Object body,Class<T> type) {
  try {
   return client.post().uri(authority.endpoint().toString().replaceAll("/+$","")+"/internal/v1/github-budget/"+action)
    .headers(h->h.setBearerAuth(authority.serviceToken())).contentType(MediaType.APPLICATION_JSON).body(body)
    .exchange((request,response)->{if(!response.getStatusCode().is2xxSuccessful())throw new CredentialUnavailableException();return type==Void.class?null:response.bodyTo(type);});
  }catch(RuntimeException error){throw new CredentialUnavailableException();}
 }
 private final class Active implements CredentialedGitHubRequest.Permit {
  private final UUID id;private final CredentialedGitHubRequest.Permit local;private final AtomicBoolean closed=new AtomicBoolean();
  private int status;private Long remaining;private Instant reset,retry;
  Active(UUID id,CredentialedGitHubRequest.Permit local){this.id=id;this.local=local;}
  @Override public void close(){
   if(!closed.compareAndSet(false,true))return;
   try {
    Map<String,Object> report=new LinkedHashMap<>();report.put("permitId",id);report.put("status",status);report.put("remaining",remaining);report.put("resetAt",reset);report.put("retryAt",retry);
    try{post("report",report,Void.class);}catch(RuntimeException unavailable){/* Durable permit expiry remains conservative; no fresh local budget is minted. */}
   }finally{active.remove();local.close();}
  }
 }
}
