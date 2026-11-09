package com.kholodilin.collector.execution;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.queue.JobQueue.Lease;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
class AssignedTaskCollectorTest {
    JsonMapper json=JsonMapper.builder().build();
    ExecutionProperties properties=new ExecutionProperties(false,null,false,null,null,null,0,0,0);
    JobRequest job(TaskType type){return new JobRequest(1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),type,new RepositoryTarget("202","owner","repo"),LocalDate.now(),1,new Parameters("spring","spring",null,50,"stars","desc"),"rgm-results",Instant.now().plusSeconds(3600),new AuthorizationScope("ctx","ref"));}
    Lease lease(JobRequest job){return new Lease(1,"worker",1,json.writeValueAsString(job),job.deadlineAt(),"rgm",UUID.randomUUID(),0);}
    class Reader implements SourceReader {
        Map<String,String> data=new HashMap<>();List<String> reads=new ArrayList<>(),invalidated=new ArrayList<>();
        public JsonNode read(Lease l,JobRequest j,String key,String method,String path,String body,boolean optional){reads.add(key);String value=data.get(key);if(value==null){if(optional)return json.readTree("null");throw new IllegalStateException("Missing fixture: "+key);}return json.readTree(value);}
        public void invalidate(Lease l,String key){invalidated.add(key);}
    }
    @Test void statsPreserveRealZerosAndCollectExactContributorsAndHealthObservations(){
        var reader=new Reader();reader.data.put("repository","{\"id\":202,\"stargazers_count\":0,\"forks_count\":0,\"subscribers_count\":0}");reader.data.put("contributors:1","[]");reader.data.put("contributor-count","{\"total\":0}");
        var job=job(TaskType.STATS);var snapshot=new AssignedTaskCollector(reader,properties).collect(lease(job),job);
        assertThat(snapshot.metrics()).containsEntry(Metric.STARS,0L).containsEntry(Metric.CONTRIBUTORS,0L);assertThat(snapshot.contributorLogins()).isEmpty();assertThat(snapshot.metadata()).containsKey("repository");assertThat(snapshot.metadata()).containsEntry("securityPolicy","false");
    }
    @Test void trafficKeepsMissingObservationsDistinctFromZero(){
        var reader=new Reader();reader.data.put("views","{\"views\":[{\"timestamp\":\"2026-11-01T00:00:00Z\",\"count\":0,\"uniques\":0}]}");reader.data.put("clones","{\"clones\":[]}");var job=job(TaskType.TRAFFIC);
        var day=new AssignedTaskCollector(reader,properties).collect(lease(job),job).traffic().getFirst();assertThat(day.views()).isZero();assertThat(day.clones()).isNull();
    }
    @Test void referrersAndPathsRemainSeparateTypedSources(){
        for(var type:List.of(TaskType.REFERRERS,TaskType.POPULAR_PATHS)){
            var reader=new Reader();reader.data.put("sources",type==TaskType.REFERRERS?"[{\"referrer\":\"example.com\",\"count\":12,\"uniques\":7}]":"[{\"path\":\"/owner/repo/issues\",\"title\":\"Issues\",\"count\":12,\"uniques\":7}]");var job=job(type);
            var source=new AssignedTaskCollector(reader,properties).collect(lease(job),job).sources().getFirst();assertThat(source.views()).isEqualTo(12);assertThat(source.visitors()).isEqualTo(7);
        }
    }
    @Test void growthProducesRawObservationsForProductOwnedDetection(){
        var reader=new Reader();reader.data.put("repository","{\"id\":202,\"stargazers_count\":10,\"forks_count\":2}");reader.data.put("contributors:1","[]");reader.data.put("contributor-count","{\"total\":0}");reader.data.put("issues:1","[]");reader.data.put("pulls:1","[]");reader.data.put("releases:1","[]");var job=job(TaskType.GROWTH_EVENTS);
        var snapshot=new AssignedTaskCollector(reader,properties).collect(lease(job),job);assertThat(snapshot.metadata()).containsKeys("repository","issues","pulls","releases","contributors");assertThat(snapshot.events()).isEmpty();
    }
    @Test void searchAndTopicsConfirmAnAbsentRankWithoutColdEnrichment(){
        for(var type:List.of(TaskType.SEARCH_QUERY,TaskType.TOPIC_WATCH)){
            var reader=new Reader();reader.data.put("search","{\"total_count\":0,\"incomplete_results\":false,\"items\":[]}");reader.data.put("search-confirmation",reader.data.get("search"));var job=job(type);
            var snapshot=new AssignedTaskCollector(reader,properties).collect(lease(job),job);assertThat(snapshot.totalResults()).isZero();assertThat(snapshot.trackedPosition()).isNull();assertThat(reader.reads).containsExactly("search","search-confirmation");assertThat(snapshot.metadata()).containsEntry("enrichmentStatus","DEFERRED");
        }
    }
    @Test void incompleteSearchNeverBecomesAFalseMissingRank(){
        var reader=new Reader();reader.data.put("search","{\"total_count\":50,\"incomplete_results\":true,\"items\":[]}");var job=job(TaskType.SEARCH_QUERY);
        assertThatThrownBy(()->new AssignedTaskCollector(reader,properties).collect(lease(job),job)).isInstanceOf(AssignedTaskCollector.Incomplete.class);assertThat(reader.invalidated).containsExactly("search");
    }

    @Test void discoveryOnlyReturnsAssignedOwnerAndNeverInventsAnIndependentSchedule(){
        var reader=new Reader();reader.data.put("accessible-repositories:1","[{\"id\":1,\"owner\":{\"login\":\"owner\"}},{\"id\":2,\"owner\":{\"login\":\"another\"}}]");var job=job(TaskType.DISCOVER_REPOSITORIES);
        var snapshot=new AssignedTaskCollector(reader,properties).collect(lease(job),job);assertThat(json.readTree(snapshot.metadata().get("repositories"))).hasSize(1);assertThat(reader.reads).containsExactly("accessible-repositories:1");
    }
    @Test void measuredHundredRepositorySourceDemandIncludesWatchesAndColdRanks(){
        Map<String,Integer> counts=new TreeMap<>();long started=System.nanoTime();
        for(int repository=0;repository<100;repository++)for(var type:List.of(TaskType.STATS,TaskType.TRAFFIC,TaskType.REFERRERS,TaskType.POPULAR_PATHS,TaskType.GROWTH_EVENTS,TaskType.SEARCH_QUERY,TaskType.TOPIC_WATCH,TaskType.TOPIC_WATCH)){
            SourceReader reader=new SourceReader(){public JsonNode read(Lease l,JobRequest j,String key,String method,String path,String body,boolean optional){
                counts.merge(path.equals("/graphql")?"graphql":path.startsWith("/search/")?"search":"core",1,Integer::sum);
                if(optional)return json.readTree("null");if(key.equals("repository"))return json.readTree("{\"id\":202,\"stargazers_count\":1,\"forks_count\":0}");
                if(key.equals("contributor-count"))return json.readTree("{\"total\":0}");if(key.equals("views"))return json.readTree("{\"views\":[]}");if(key.equals("clones"))return json.readTree("{\"clones\":[]}");
                if(key.startsWith("search"))return json.readTree("{\"total_count\":1,\"incomplete_results\":false,\"items\":[{\"id\":303,\"full_name\":\"cold/project\",\"stargazers_count\":10,\"forks_count\":1}]}");return json.readTree("[]");
            }public void invalidate(Lease lease,String key){}};
            var job=job(type);assertThat(new AssignedTaskCollector(reader,properties).collect(lease(job),job).complete()).isTrue();
        }
        assertThat(counts).containsEntry("search",600).containsEntry("graphql",200);assertThat(counts.get("core")).isGreaterThan(2000);
        System.out.printf("SOURCE WORKLOAD 100 repositories, 100 queries, 200 watches, cold absent rank confirmation: %s requests; CPU fixture duration=%dms%n",counts,(System.nanoTime()-started)/1_000_000);
    }
}
