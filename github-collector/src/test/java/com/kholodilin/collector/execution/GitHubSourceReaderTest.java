package com.kholodilin.collector.execution;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.credentials.*;
import com.kholodilin.collector.queue.JobQueue;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class GitHubSourceReaderTest {
    HttpServer server;URI endpoint;AtomicInteger permits=new AtomicInteger(),reports=new AtomicInteger(),resolved=new AtomicInteger(),calls=new AtomicInteger();
    List<String> resources=new ArrayList<>();HttpHandler source;GitHubSourceReader reader;Map<String,String> stored=new HashMap<>();
    JsonMapper json=JsonMapper.builder().build();
    JobRequest job=new JobRequest(1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),TaskType.STATS,new RepositoryTarget("202","owner","repo"),LocalDate.now(),1,Parameters.repository(),"results",Instant.now().plusSeconds(600),new AuthorizationScope("ctx","main"));
    JobQueue.Lease lease=new JobQueue.Lease(1,"worker",1,"{}",job.deadlineAt(),"rgm",UUID.randomUUID(),0);
    @BeforeEach void setup()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        server.createContext("/internal/v1/github-budget/acquire",exchange->{resources.add(json.readTree(exchange.getRequestBody().readAllBytes()).path("resource").asText());permits.incrementAndGet();respond(exchange,200,json.writeValueAsString(Map.of("permitId",UUID.randomUUID(),"leasedUntil",Instant.now().plusSeconds(60))));});
        server.createContext("/internal/v1/github-budget/report",exchange->{reports.incrementAndGet();exchange.getRequestBody().readAllBytes();respond(exchange,204,"");});
        server.createContext("/",exchange->{calls.incrementAndGet();source.handle(exchange);});server.start();
        var credentials=new CollectorCredentialProperties(true,null,0,0,null,Duration.ofSeconds(1),Duration.ofSeconds(1),true,List.of(new CollectorCredentialProperties.Origin("rgm",endpoint,"node-service-secret-0123456789012345")));
        var cache=new CredentialCache((origin,request)->{int version=resolved.incrementAndGet();return new CredentialLease(1,"ctx","main",Integer.toString(version),"github-token-"+version,null,Instant.now().plusSeconds(600));},credentials,Clock.systemUTC(),com.github.benmanes.caffeine.cache.Ticker.systemTicker());
        var observations=new ObservationStore(){public Optional<String> find(JobQueue.Lease lease,String key){return Optional.ofNullable(stored.get(key));}public void save(JobQueue.Lease lease,String key,String payload){stored.put(key,payload);}public void remove(JobQueue.Lease lease,String key){stored.remove(key);}};
        var pacer=mock(LocalGitHubPacer.class);when(pacer.tryAcquire(anyString(),anyString())).thenAnswer(invocation->Optional.of((CredentialedGitHubRequest.Permit)()->{}));
        reader=new GitHubSourceReader(observations,cache,credentials,new ExecutionProperties(false,endpoint,true,null,null,Duration.ofSeconds(1),0,0,0),pacer);
    }
    void respond(HttpExchange exchange,int status,String text)throws java.io.IOException{byte[] bytes=text.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,status==204?-1:bytes.length);if(status!=204)exchange.getResponseBody().write(bytes);exchange.close();}
    @AfterEach void stop(){server.stop(0);}
    @Test void checkpointsReuseObservedZeroAndEveryNetworkCallReportsItsPermit(){
        source=exchange->{assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer github-token-1");respond(exchange,200,"{\"stargazers_count\":0}");};
        assertThat(reader.read(lease,job,"repository","GET","/repos/owner/repo",null,false).path("stargazers_count").asInt()).isZero();reader.read(lease,job,"repository","GET","/repos/owner/repo",null,false);
        assertThat(calls.get()).isEqualTo(1);assertThat(permits.get()).isEqualTo(1);assertThat(reports.get()).isEqualTo(1);assertThat(resources).containsExactly("core");assertThat(stored.values().toString()).doesNotContain("github-token");
    }
    @Test void rejectedTokenRefreshesExactlyOnceAndRetryGetsANewSharedPermit(){
        source=exchange->respond(exchange,calls.get()==1?401:200,"{}");reader.read(lease,job,"repository","GET","/repos/owner/repo",null,false);
        assertThat(resolved.get()).isEqualTo(2);assertThat(permits.get()).isEqualTo(2);assertThat(reports.get()).isEqualTo(2);
    }
    @Test void rateLimitIsDeferredWithoutRefreshingTheCredential(){
        source=exchange->{exchange.getResponseHeaders().set("Retry-After","60");respond(exchange,429,"{}");};
        assertThatThrownBy(()->reader.read(lease,job,"search","GET","/search/repositories?q=test",null,false)).isInstanceOf(CredentialedGitHubRequest.Failure.class);assertThat(resolved.get()).isEqualTo(1);assertThat(resources).containsExactly("search");assertThat(stored).isEmpty();
    }
    @Test void contributorLinkCountAndMissingOptionalFilePreserveMeaning(){
        source=exchange->{exchange.getResponseHeaders().set("Link","<https://api.github.com/repos/owner/repo/contributors?per_page=1&page=137>; rel=\"last\"");respond(exchange,200,"[{\"login\":\"someone\"}]");};
        assertThat(reader.read(lease,job,"contributor-count","GET","/repos/owner/repo/contributors?per_page=1",null,false).path("total").asInt()).isEqualTo(137);
        source=exchange->respond(exchange,404,"{}");assertThat(reader.read(lease,job,"readme","GET","/repos/owner/repo/readme",null,true).isNull()).isTrue();assertThat(resolved.get()).isEqualTo(1);
    }
    @Test void redirectsNeverCarryTheGithubSecretToAnotherEndpoint(){
        source=exchange->{exchange.getResponseHeaders().set("Location",endpoint+"/leak");respond(exchange,307,"");};
        assertThatThrownBy(()->reader.read(lease,job,"repository","GET","/repos/owner/repo",null,false)).isInstanceOf(CredentialedGitHubRequest.Failure.class);assertThat(calls.get()).isEqualTo(1);
    }
}
