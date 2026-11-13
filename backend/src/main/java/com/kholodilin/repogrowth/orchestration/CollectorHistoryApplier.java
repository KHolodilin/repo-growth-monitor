package com.kholodilin.repogrowth.orchestration;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.repository.domain.RepositoryHealthFacts;
import com.kholodilin.repogrowth.repository.application.RepositoryHealthEvaluator;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import com.kholodilin.repogrowth.traffic.ServicePathClassifier;
import com.kholodilin.repogrowth.topic.application.TopicWatchSync;
import com.kholodilin.repogrowth.github.model.*;
import com.kholodilin.repogrowth.event.detect.*;
import com.kholodilin.repogrowth.event.domain.*;
import com.kholodilin.repogrowth.event.persistence.*;
import com.kholodilin.repogrowth.search.application.RepositoryEnricher;
import com.kholodilin.repogrowth.search.persistence.*;
import com.kholodilin.repogrowth.topic.persistence.*;
import com.kholodilin.repogrowth.topic.domain.TopicResult;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;

/** Product-side interpretation only. Called inside the receipt's owning product transaction. */
@Component
@ConditionalOnProperty(name="rgm.orchestration.enabled",havingValue="true")
public class CollectorHistoryApplier {
    @org.springframework.beans.factory.annotation.Autowired private com.kholodilin.repogrowth.publication.PublicationService publication;
    private final JdbcTemplate jdbc;private final JsonMapper json;private final RepositoryJdbcRepository repositories;
    private final TrafficJdbcRepository traffic;private final TopicWatchSync watches;private final ServicePathClassifier paths;
    private final GrowthEventStateJdbcRepository eventState;private final GrowthEventJdbcRepository events;
    private final SearchRunJdbcRepository searchRuns;private final SearchResultJdbcRepository searchResults;private final RepositoryEnricher enricher;
    private final TopicRunJdbcRepository topicRuns;private final TopicResultJdbcRepository topicResults;
    public CollectorHistoryApplier(JdbcTemplate jdbc,JsonMapper json,RepositoryJdbcRepository repositories,TrafficJdbcRepository traffic,TopicWatchSync watches,ServicePathClassifier paths,
            GrowthEventStateJdbcRepository eventState,GrowthEventJdbcRepository events,SearchRunJdbcRepository searchRuns,SearchResultJdbcRepository searchResults,RepositoryEnricher enricher,TopicRunJdbcRepository topicRuns,TopicResultJdbcRepository topicResults){
        this.jdbc=jdbc;this.json=json;this.repositories=repositories;this.traffic=traffic;this.watches=watches;this.paths=paths;this.eventState=eventState;this.events=events;this.searchRuns=searchRuns;this.searchResults=searchResults;this.enricher=enricher;this.topicRuns=topicRuns;this.topicResults=topicResults;
    }
    private <T>T read(Snapshot snapshot,String key,Class<T> type){String raw=snapshot.metadata().get(key);return raw==null?null:json.readValue(raw,type);}
    private <T>List<T> list(Snapshot snapshot,String key,Class<T> type){String raw=snapshot.metadata().get(key);if(raw==null)return List.of();var array=json.readTree(raw);List<T> values=new ArrayList<>();for(var value:array)values.add(json.treeToValue(value,type));return values;}
    private Instant commit(Snapshot snapshot,String key){var items=list(snapshot,key,GitHubCommitResponse.class);return items.isEmpty()?null:items.getFirst().committedAt();}
    private int metric(Snapshot snapshot,Metric metric,int fallback){Long value=snapshot.metrics().get(metric);return value==null?fallback:Math.toIntExact(value);}
    private static Timestamp ts(Instant instant){return Timestamp.from(instant);}
    public void apply(Map<String,Object> task,JobRequest job,ResultDelivery result){
        long repository=((Number)task.get("repository_id")).longValue();long taskId=((Number)task.get("id")).longValue();
        if(result.status()==JobState.FAILED && job.type()==TaskType.DISCOVER_REPOSITORIES)return;
        if(result.status()==JobState.FAILED){if(result.failure().code()==ErrorCode.PERMISSION_DENIED && Set.of(TaskType.TRAFFIC,TaskType.REFERRERS,TaskType.POPULAR_PATHS).contains(job.type()))repositories.setTrafficPermissionMissing(repository,true);legacy(taskId,repository,job,result);return;}
        Snapshot snapshot=result.snapshot();
        switch(job.type()){
            case DISCOVER_REPOSITORIES -> discover(job,snapshot);
            case STATS -> stats(repository,job,snapshot);
            case TRAFFIC -> {
                for(var day:snapshot.traffic()){
                    if(day.views()!=null && day.visitors()!=null && day.clones()!=null && day.uniqueCloners()!=null)traffic.upsertDaily(repository,day.date(),Math.toIntExact(day.views()),Math.toIntExact(day.visitors()),Math.toIntExact(day.clones()),Math.toIntExact(day.uniqueCloners()));
                    else jdbc.update("""
                            INSERT INTO traffic_daily (repository_id, traffic_date, views, unique_visitors, clones, unique_cloners)
                            VALUES (?, ?, ?, ?, ?, ?)
                            ON CONFLICT (repository_id, traffic_date) DO UPDATE SET
                              views = coalesce(EXCLUDED.views, traffic_daily.views),
                              unique_visitors = coalesce(EXCLUDED.unique_visitors, traffic_daily.unique_visitors),
                              clones = coalesce(EXCLUDED.clones, traffic_daily.clones),
                              unique_cloners = coalesce(EXCLUDED.unique_cloners, traffic_daily.unique_cloners),
                              updated_at = clock_timestamp()
                            """, repository, day.date(), day.views(), day.visitors(), day.clones(), day.uniqueCloners());
                }
                repositories.setTrafficPermissionMissing(repository,false);
            }
            case REFERRERS,POPULAR_PATHS -> {
                LocalDate date=snapshot.observedAt().atZone(ZoneOffset.UTC).toLocalDate();
                if(job.type()==TaskType.REFERRERS)traffic.deleteReferrerSnapshot(repository,date);else traffic.deletePathSnapshot(repository,date);
                for(var row:snapshot.sources())if(job.type()==TaskType.REFERRERS)traffic.insertReferrers(repository,date,snapshot.observedAt(),row.key(),Math.toIntExact(row.views()),Math.toIntExact(row.visitors()));else traffic.insertPath(repository,date,snapshot.observedAt(),row.key(),row.title(),Math.toIntExact(row.views()),Math.toIntExact(row.visitors()),paths.isServicePath(row.key()));
                jdbc.update("INSERT INTO collector_snapshot_header VALUES (?,?,?,?) ON CONFLICT(repository_id,family,business_date) DO UPDATE SET observed_at=excluded.observed_at",repository,job.type().name(),date,ts(snapshot.observedAt()));repositories.setTrafficPermissionMissing(repository,false);
            }
            case GROWTH_EVENTS -> growth(repository,task,job,snapshot);
            case SEARCH_QUERY,TOPIC_WATCH -> rank(taskId,repository,job,snapshot,(String)task.get("target_key"));
        }
        if(job.type()!=TaskType.DISCOVER_REPOSITORIES && job.type()!=TaskType.SEARCH_QUERY && job.type()!=TaskType.TOPIC_WATCH)legacy(taskId,repository,job,result);
    }
    private void discover(JobRequest job,Snapshot snapshot){
        for(var remote:list(snapshot,"repositories",GitHubRepositoryResponse.class)){
            if(remote.owner()==null || !remote.owner().login().equalsIgnoreCase(job.repository().owner()))throw new IllegalArgumentException("Discovery owner identity mismatch");
            long owner=jdbc.queryForObject("SELECT id FROM github_owner WHERE lower(login)=lower(?)",Long.class,job.repository().owner());
            var existing=repositories.findByGithubId(remote.id());
            // Keep the stored visibility until PublicationService.verify() accepts this observation.
            // Writing remote visibility here would let a stale PUBLIC discovery reopen a newer PRIVATE verification.
            String visibility=existing.map(value->value.visibility()).orElse(remote.resolvedVisibility());
            var stored=repositories.upsertKeepingTracking(new com.kholodilin.repogrowth.repository.domain.Repository(null,remote.id(),owner,remote.name(),remote.fullName(),remote.description(),visibility,remote.defaultBranch(),remote.language(),remote.fork(),remote.archived(),remote.stargazersCount(),remote.watchers(),remote.forksCount(),remote.openIssuesCount(),existing.map(value->value.contributors()).orElse(0),false,remote.createdAt(),remote.updatedAt(),remote.pushedAt(),null,null,null,null,null));
            publication.metadata(stored.id(),remote,snapshot.observedAt());
            repositories.markAccountAccessible(stored.id());repositories.replaceTopics(stored.id(),remote.topicsOrEmpty());
        }
    }
    private void stats(long id,JobRequest job,Snapshot snapshot){
        var current=repositories.findById(id).orElseThrow();var remote=Objects.requireNonNull(read(snapshot,"repository",GitHubRepositoryResponse.class));
        if(remote.id()!=current.githubId())throw new IllegalArgumentException("Source repository identity mismatch");
        publication.metadata(id,remote,snapshot.observedAt());
        int contributors=metric(snapshot,Metric.CONTRIBUTORS,current.contributors());int watchers=metric(snapshot,Metric.WATCHERS,current.watchers());
        repositories.updateStats(id,remote.description(),remote.stargazersCount(),watchers,remote.forksCount(),remote.openIssuesCount(),contributors,remote.updatedAt(),remote.pushedAt(),commit(snapshot,"latestCommits"),snapshot.observedAt(),remote.archived());
        repositories.replaceTopics(id,remote.topicsOrEmpty());watches.sync(id);
        var readme=read(snapshot,"readme",GitHubReadmeResponse.class);String text=readme==null?null:readme.decodedText();
        var community=read(snapshot,"community",GitHubCommunityProfileResponse.class);var files=community==null?GitHubCommunityProfileResponse.GitHubCommunityFiles.empty():community.filesOrEmpty();
        var previous=repositories.findHealth(id).orElse(RepositoryHealthFacts.empty());var preview=read(snapshot,"socialPreview",tools.jackson.databind.JsonNode.class);
        boolean custom=preview!=null && preview.hasNonNull("usesCustomOpenGraphImage")?preview.get("usesCustomOpenGraphImage").asBoolean():previous.hasSocialPreview();
        repositories.upsertHealth(id,new RepositoryHealthFacts(remote.hasHomepage()?remote.homepage().trim():null,files.hasReadme() || text!=null,RepositoryHealthEvaluator.readmeHasH1(text),RepositoryHealthEvaluator.readmeHasName(text,current.name()),files.hasLicense() || remote.hasLicense(),community==null?previous.hasCodeOfConduct():files.hasCodeOfConduct(),community==null?previous.hasContributing():files.hasContributing(),Boolean.parseBoolean(snapshot.metadata().getOrDefault("securityPolicy",Boolean.toString(previous.hasSecurityPolicy()))),files.hasIssueTemplate() || Boolean.parseBoolean(snapshot.metadata().getOrDefault("issueTemplate","false")),community==null?previous.hasPullRequestTemplate():files.hasPullRequestTemplate(),custom));
        if("true".equals(snapshot.metadata().get("releaseFetched"))){var release=read(snapshot,"latestRelease",GitHubReleaseItem.class);repositories.updateLastReleaseAt(id,release==null?null:release.timestamp());}
        traffic.upsertDailyStats(id,job.businessDate(),remote.stargazersCount(),watchers,remote.forksCount(),remote.openIssuesCount(),contributors);if(snapshot.contributorLogins()!=null)traffic.replaceContributorLogins(id,job.businessDate(),snapshot.contributorLogins());
    }
    private void growth(long id,Map<String,Object> task,JobRequest job,Snapshot snapshot){
        var remote=Objects.requireNonNull(read(snapshot,"repository",GitHubRepositoryResponse.class));var readme=read(snapshot,"readme",GitHubReadmeResponse.class);var preview=read(snapshot,"socialPreview",tools.jackson.databind.JsonNode.class);
        Boolean custom=preview!=null && preview.hasNonNull("usesCustomOpenGraphImage")?preview.get("usesCustomOpenGraphImage").asBoolean():null;String previewUrl=preview!=null && preview.hasNonNull("openGraphImageUrl")?preview.get("openGraphImageUrl").asText():null;
        var observation=new GitHubActivitySnapshot(remote,readme==null?null:readme.decodedText(),readme==null?null:readme.sha(),commit(snapshot,"readmeCommits"),list(snapshot,"issues",GitHubIssueItem.class),list(snapshot,"pulls",GitHubPullItem.class),list(snapshot,"releases",GitHubReleaseItem.class),list(snapshot,"contributors",GitHubContributorItem.class),custom,previewUrl);
        var previous=eventState.find(id);var settings=json.readTree(task.get("configuration_snapshot").toString()).path("growthEventSettings");
        for(var candidate:GrowthEventDetections.detect(previous,observation,job.repository().owner(),snapshot.observedAt()))if(settings.path(candidate.type()).asBoolean(false))events.insertIgnore(id,candidate);
        if(observation.readmeCommittedAt()!=null)events.pullEventAtBack(id,GrowthEventCatalog.README_SIGNIFICANTLY_CHANGED,observation.readmeCommittedAt());
        if(remote.updatedAt()!=null)for(String type:List.of(GrowthEventCatalog.DESCRIPTION_CHANGED,GrowthEventCatalog.TOPICS_CHANGED,GrowthEventCatalog.HOMEPAGE_CHANGED))events.pullEventAtBack(id,type,remote.updatedAt());
        eventState.upsert(id,GrowthEventDetections.snapshot(previous,observation,job.repository().owner()));
    }
    private void rank(long task,long repository,JobRequest job,Snapshot snapshot,String target){
        long targetId=Long.parseLong(target.substring(target.indexOf(':')+1));boolean topic=job.type()==TaskType.TOPIC_WATCH;
        String table=topic?"topic_run":"search_run",column=topic?"topic_watch_id":"search_query_id";
        // Existing history remains a daily compatibility view; no run rows are moved/deleted.
        jdbc.update("INSERT INTO "+table+"("+column+",repository_id,business_date,status) VALUES (?,?,?,'READY') ON CONFLICT DO NOTHING",targetId,repository,job.businessDate());
        long run=jdbc.queryForObject("SELECT id FROM "+table+" WHERE "+column+"=? AND business_date=?",Long.class,targetId,job.businessDate());
        var raw=json.readTree(snapshot.metadata().getOrDefault("search","{\"items\":[]}"));
        if(topic){List<TopicResult> rows=new ArrayList<>();int pos=1;for(var value:raw.path("items")){var item=json.treeToValue(value,GitHubSearchItem.class);rows.add(new TopicResult(null,run,pos++,item.id(),item.fullName(),item.owner()==null?"":item.owner().login(),item.stargazersCount(),0,item.forksCount(),item.language(),item.description(),item.htmlUrl()));}topicResults.replaceAll(run,rows);topicRuns.markSuccess(run,Math.toIntExact(snapshot.totalResults()),snapshot.trackedPosition());}
        else{List<com.kholodilin.repogrowth.search.domain.SearchResult> rows=new ArrayList<>();int pos=1;for(var value:raw.path("items"))rows.add(enricher.fromSearchItem(run,pos++,json.treeToValue(value,GitHubSearchItem.class)));searchResults.replaceAll(run,rows);searchRuns.markSuccess(run,Math.toIntExact(snapshot.totalResults()),snapshot.trackedPosition());searchRuns.markEnrichment(run,"PARTIAL");}
        boolean publicRows=job.parameters().publicOnly();for(var item:raw.path("items"))if(!item.hasNonNull("private") || !item.get("private").isBoolean() || item.get("private").asBoolean())publicRows=false;
        jdbc.update("UPDATE "+table+" SET snapshot_at=?,completed_at=?,public_scope_verified=? WHERE id=?",ts(snapshot.observedAt()),ts(snapshot.observedAt()),publicRows,run);
        jdbc.update("INSERT INTO collection_history_mapping VALUES (?,?,?) ON CONFLICT(task_id) DO NOTHING",task,job.type().name(),run);
    }
    private void legacy(long task,long repository,JobRequest job,ResultDelivery result){
        boolean rank=job.type()==TaskType.SEARCH_QUERY || job.type()==TaskType.TOPIC_WATCH;
        if(rank){long target=Long.parseLong(jdbc.queryForObject("SELECT target_key FROM collection_task WHERE id=?",String.class,task).split(":")[1]);String table=job.type()==TaskType.SEARCH_QUERY?"search_run":"topic_run",column=job.type()==TaskType.SEARCH_QUERY?"search_query_id":"topic_watch_id";
            jdbc.update("INSERT INTO "+table+"("+column+",repository_id,business_date,status,error_code,error_message,completed_at) VALUES (?,?,?,'FAILED',?,?,clock_timestamp()) ON CONFLICT("+column+",business_date) DO UPDATE SET status='FAILED',error_code=excluded.error_code,error_message=excluded.error_message,completed_at=excluded.completed_at",target,repository,job.businessDate(),result.failure().code().name(),result.failure().message());return;}
        long run=jdbc.queryForObject("INSERT INTO collection_run(repository_id,business_date,status,planned_jobs) VALUES (?,?,'RUNNING',5) ON CONFLICT(repository_id,business_date) DO UPDATE SET planned_jobs=greatest(collection_run.planned_jobs,5) RETURNING id",Long.class,repository,job.businessDate());
        String type=job.type()==TaskType.STATS?"REPOSITORY_STATS":job.type().name(),state=result.status()==JobState.SUCCEEDED?"SUCCESS":"FAILED";
        jdbc.update("INSERT INTO collection_job(collection_run_id,repository_id,job_type,business_date,status,completed_at,error_code,error_message) VALUES (?,?,?,?,?,clock_timestamp(),?,?) ON CONFLICT(repository_id,business_date,job_type) DO UPDATE SET status=excluded.status,completed_at=excluded.completed_at,error_code=excluded.error_code,error_message=excluded.error_message",run,repository,type,job.businessDate(),state,result.failure()==null?null:result.failure().code().name(),result.failure()==null?null:result.failure().message());
        jdbc.update("UPDATE collection_run r SET successful_jobs=(SELECT count(*) FROM collection_job j WHERE j.collection_run_id=r.id AND j.status='SUCCESS'),failed_jobs=(SELECT count(*) FROM collection_job j WHERE j.collection_run_id=r.id AND j.status='FAILED'),status=CASE WHEN (SELECT count(*) FROM collection_job j WHERE j.collection_run_id=r.id AND j.status IN ('SUCCESS','FAILED'))>=r.planned_jobs THEN CASE WHEN EXISTS(SELECT 1 FROM collection_job j WHERE j.collection_run_id=r.id AND j.status='FAILED') THEN 'PARTIAL' ELSE 'SUCCESS' END ELSE 'RUNNING' END,completed_at=CASE WHEN (SELECT count(*) FROM collection_job j WHERE j.collection_run_id=r.id AND j.status IN ('SUCCESS','FAILED'))>=r.planned_jobs THEN clock_timestamp() ELSE NULL END WHERE id=?",run);
        jdbc.update("INSERT INTO collection_history_mapping VALUES (?,?,?) ON CONFLICT(task_id) DO NOTHING",task,job.type().name(),run);
    }
}
