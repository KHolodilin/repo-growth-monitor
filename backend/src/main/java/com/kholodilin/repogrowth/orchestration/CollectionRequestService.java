package com.kholodilin.repogrowth.orchestration;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.contracts.CollectorProtocol;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.api.ErrorCode;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.topic.persistence.TopicWatchJdbcRepository;
import com.kholodilin.repogrowth.topic.application.TopicWatchSync;
import com.kholodilin.repogrowth.event.persistence.GrowthEventSettingJdbcRepository;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="rgm.orchestration.enabled",havingValue="true")
public class CollectionRequestService {
    public record Created(UUID requestId,List<UUID> taskIds,boolean coalesced) {
        public Created {taskIds=List.copyOf(taskIds);}
    }
    public record ScheduledCounts(int repositories,int queries,int watches) { }
    private record Spec(TaskType type,String key,Parameters parameters) { }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.kholodilin.repogrowth.credentials.CredentialVault credentialVault;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private CollectorNodeMonitor nodeMonitor;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OrchestrationProperties properties;
    private final RepositoryJdbcRepository repositories;
    private final SearchQueryJdbcRepository queries;
    private final TopicWatchJdbcRepository watches;
    private final JsonMapper json;
    private final TopicWatchSync watchSync;
    private final GrowthEventSettingJdbcRepository eventSettings;
    public CollectionRequestService(JdbcTemplate jdbc,PlatformTransactionManager tm,OrchestrationProperties properties,
            RepositoryJdbcRepository repositories,SearchQueryJdbcRepository queries,TopicWatchJdbcRepository watches,JsonMapper json,TopicWatchSync watchSync,GrowthEventSettingJdbcRepository eventSettings) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(tm);this.properties=properties;
        this.watchSync=watchSync;this.eventSettings=eventSettings;this.repositories=repositories;this.queries=queries;this.watches=watches;this.json=json;
    }
    public Created manual(long repositoryId,LocalDate date) { return create(repositoryId,date,null); }
    public ScheduledCounts scheduled(LocalDate date,String slot) {
        int count=0,queryCount=0,watchCount=0;
        for(var repository:repositories.findTracked()) {
            create(repository.id(),date,"scheduled:"+repository.id()+":"+date+":"+(slot==null?"daily":slot)); count++;
            queryCount+=(int)queries.findByRepository(repository.id()).stream().filter(q->q.enabled()).count();
            watchCount+=(int)watches.findByRepository(repository.id()).stream().filter(w->w.enabled()).count();
        }
        return new ScheduledCounts(count,queryCount,watchCount);
    }
    public List<Created> refresh(LocalDate date){
        // One explicit discovery task per owner/credential scope. The Collector never discovers work.
        var seeds=jdbc.queryForList("SELECT min(r.id) FROM repository r JOIN repository_credential b ON b.repository_id=r.id JOIN github_credential c ON c.id=b.credential_id AND NOT c.revoked GROUP BY r.owner_id,b.credential_id ORDER BY min(r.id)",Long.class);
        if(seeds.isEmpty())throw ApiException.validation("Configure a repository credential before refreshing repositories");
        return seeds.stream().map(id->create(id,date,null,true)).toList();
    }
    private Created create(long repositoryId,LocalDate date,String triggerKey){return create(repositoryId,date,triggerKey,false);}
    public Created searchCommand(long id,LocalDate date){var query=queries.findById(id).orElseThrow(()->ApiException.notFound("Search query not found"));return create(query.repositoryId(),date,null,false,List.of(new Spec(TaskType.SEARCH_QUERY,"query:"+id,new Parameters(query.query(),null,null,query.resultLimit(),"stars","desc",searchPublic(query.id())))));}
    public Created searchAllCommand(long id,LocalDate date){var specs=queries.findByRepository(id).stream().filter(q->q.enabled()).map(q->new Spec(TaskType.SEARCH_QUERY,"query:"+q.id(),new Parameters(q.query(),null,null,q.resultLimit(),"stars","desc",searchPublic(q.id())))).toList();if(specs.isEmpty())throw ApiException.validation("No enabled search queries");return create(id,date,null,false,specs);}
    public Created topicCommand(long id,LocalDate date){var watch=watches.findById(id).orElseThrow(()->ApiException.notFound("Topic watch not found"));return create(watch.repositoryId(),date,null,false,List.of(new Spec(TaskType.TOPIC_WATCH,"watch:"+id,new Parameters(watch.searchQuery(),watch.topic(),watch.language(),watch.resultLimit(),watch.sort(),watch.sortOrder(),topicsPublic(watch.repositoryId())))));}
    public Created topicsCommand(long id,String scope,LocalDate date){if(!Set.of("all","language").contains(scope))throw ApiException.validation("Topic scope must be all or language");watchSync.sync(id);var specs=watches.findEnabledByRepository(id,scope.equals("all")).stream().map(w->new Spec(TaskType.TOPIC_WATCH,"watch:"+w.id(),new Parameters(w.searchQuery(),w.topic(),w.language(),w.resultLimit(),w.sort(),w.sortOrder(),topicsPublic(w.repositoryId())))).toList();if(specs.isEmpty())throw ApiException.validation("No enabled topic watches");return create(id,date,null,false,specs);}
    private Created create(long repositoryId,LocalDate date,String triggerKey,boolean refresh){return create(repositoryId,date,triggerKey,refresh,null);}
    private Created create(long repositoryId,LocalDate date,String triggerKey,boolean refresh,List<Spec> requested) {
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,repositoryId);
            // Serialize creation without blocking history writers under the request lock.
            var rows=jdbc.queryForList("SELECT r.*,o.login AS owner_login FROM repository r JOIN github_owner o ON o.id=r.owner_id WHERE r.id=?",repositoryId);
            if(rows.isEmpty()) throw ApiException.notFound("Repository not found");
            var repository=rows.getFirst();
            var existing=jdbc.queryForList("SELECT id,public_id FROM collection_request WHERE repository_id=? AND (state='ACTIVE' OR (?::text IS NOT NULL AND trigger_key=?)) ORDER BY (trigger_key=?::text) DESC NULLS LAST,id DESC LIMIT 1",repositoryId,triggerKey,triggerKey,triggerKey);
            if(!existing.isEmpty()) {
                long request=((Number)existing.getFirst().get("id")).longValue();
                if(requested==null&&!refresh){
                    String state=jdbc.queryForObject("SELECT state FROM collection_request WHERE id=? FOR UPDATE",String.class,request);
                    if(!state.equals("ACTIVE")){if(triggerKey!=null)return response(request,(UUID)existing.getFirst().get("public_id"),true);return create(repositoryId,date,triggerKey,false,null);}
                    watchSync.sync(repositoryId);
                    for(var type:List.of(TaskType.STATS,TaskType.TRAFFIC,TaskType.REFERRERS,TaskType.POPULAR_PATHS,TaskType.GROWTH_EVENTS))append(request,repositoryId,new Spec(type,"repository",Parameters.repository()));
                    for(var query:queries.findByRepository(repositoryId))if(query.enabled())append(request,repositoryId,new Spec(TaskType.SEARCH_QUERY,"query:"+query.id(),new Parameters(query.query(),null,null,query.resultLimit(),"stars","desc",searchPublic(query.id()))));
                    for(var watch:watches.findByRepository(repositoryId))if(watch.enabled())append(request,repositoryId,new Spec(TaskType.TOPIC_WATCH,"watch:"+watch.id(),new Parameters(watch.searchQuery(),watch.topic(),watch.language(),watch.resultLimit(),watch.sort(),watch.sortOrder(),topicsPublic(watch.repositoryId()))));
                }
                if(requested!=null){
                    String state=jdbc.queryForObject("SELECT state FROM collection_request WHERE id=? FOR UPDATE",String.class,request);
                    if(!state.equals("ACTIVE"))return create(repositoryId,date,null,false,requested);
                    for(var spec:requested)append(request,repositoryId,spec);
                }
                if(refresh){
                    String state=jdbc.queryForObject("SELECT state FROM collection_request WHERE id=? FOR UPDATE",String.class,request);
                    if(!state.equals("ACTIVE"))return create(repositoryId,date,null,true);
                    if(jdbc.queryForObject("SELECT count(*) FROM collection_task WHERE request_id=? AND task_type='DISCOVER_REPOSITORIES'",Long.class,request)==0){
                        var raw=jdbc.queryForObject("SELECT d.payload::text FROM collection_task t JOIN collector_dispatch d ON d.attempt_id=t.current_attempt_id WHERE t.request_id=? ORDER BY t.id LIMIT 1",String.class,request);
                        var parent=json.readValue(raw,JobRequest.class);var taskId=UUID.randomUUID();var attemptId=UUID.randomUUID();
                        var authority=credentialVault.scope(repositoryId);var payload=new JobRequest(1,parent.requestId(),taskId,attemptId,TaskType.DISCOVER_REPOSITORIES,parent.repository(),date,parent.configurationRevision(),Parameters.repository(),parent.destinationRef(),Instant.now().plus(properties.jobTimeout()),authority);
                        Long nodeId=jdbc.queryForObject("SELECT a.node_id FROM collection_attempt a WHERE public_id=?",Long.class,parent.attemptId());
                        Long task=jdbc.queryForObject("INSERT INTO collection_task(public_id,request_id,repository_id,task_type,target_key,parameters,configuration_snapshot,configuration_revision,current_attempt_id,authorization_context,credential_ref) VALUES (?,?,?,'DISCOVER_REPOSITORIES','repository','{}','{}',?,?,?::uuid,?) RETURNING id",Long.class,taskId,request,repositoryId,payload.configurationRevision(),attemptId,authority.context(),authority.credentialRef());
                        jdbc.update("INSERT INTO collection_attempt(public_id,task_id,attempt_no,node_id) VALUES (?,?,1,?)",attemptId,task,nodeId);
                        String endpoint=jdbc.queryForObject("SELECT endpoint FROM collector_node WHERE id=?",String.class,nodeId);
                        jdbc.update("INSERT INTO collector_dispatch(attempt_id,endpoint,payload,deadline_at) VALUES (?,?,?::jsonb,?)",attemptId,endpoint,json.writeValueAsString(payload),Timestamp.from(payload.deadlineAt()));
                    }
                }
                return response(request,(UUID)existing.getFirst().get("public_id"),true);
            }
            String owner=(String)repository.get("owner_login"), githubId=repository.get("github_id").toString();
            AuthorizationScope authorization=credentialVault==null?null:credentialVault.scope(repositoryId);
            String pool=properties.poolFor(owner,githubId);
            var candidates=properties.nodes().stream().filter(n->n.enabled() && n.pool().equals(pool) && (nodeMonitor==null || nodeMonitor.eligible(n.key()))).sorted(Comparator.comparing(OrchestrationProperties.Node::key)).toList();
            if(candidates.isEmpty()) throw new ApiException(ErrorCode.INTERNAL_ERROR,HttpStatus.SERVICE_UNAVAILABLE,"No collector is configured for the repository pool");
            // Stable spread within a pool. Repeated sends use the persisted assignment, never re-route.
            var node=candidates.get(Math.floorMod(githubId.hashCode(),candidates.size()));
            Long nodeId=jdbc.queryForObject("INSERT INTO collector_node(node_key,pool,endpoint,enabled) VALUES (?,?,?,true) ON CONFLICT(node_key) DO UPDATE SET pool=excluded.pool,endpoint=excluded.endpoint,enabled=true,updated_at=clock_timestamp() RETURNING id",Long.class,node.key(),node.pool(),OrchestrationProperties.endpoint(node));
            UUID requestId=UUID.randomUUID();
            Long request=jdbc.queryForObject("INSERT INTO collection_request(public_id,repository_id,trigger_type,trigger_key,business_date) VALUES (?,?,?,?,?) RETURNING id",Long.class,requestId,repositoryId,triggerKey==null?"MANUAL":"SCHEDULED",triggerKey,date);
            var target=new RepositoryTarget(githubId,owner,(String)repository.get("name"));
            watchSync.sync(repositoryId);
            Map<String,Boolean> growthSettings=new TreeMap<>();
            for(var setting:eventSettings.ensureDefaults(repositoryId)) growthSettings.put(setting.eventType(),setting.enabled());
            List<Spec> specs=new ArrayList<>();
            for(var type:List.of(TaskType.STATS,TaskType.TRAFFIC,TaskType.REFERRERS,TaskType.POPULAR_PATHS,TaskType.GROWTH_EVENTS)) specs.add(new Spec(type,"repository",Parameters.repository()));
            for(var query:queries.findByRepository(repositoryId)) if(query.enabled()) specs.add(new Spec(TaskType.SEARCH_QUERY,"query:"+query.id(),new Parameters(query.query(),null,null,query.resultLimit(),"stars","desc",searchPublic(query.id()))));
            for(var watch:watches.findByRepository(repositoryId)) if(watch.enabled()) specs.add(new Spec(TaskType.TOPIC_WATCH,"watch:"+watch.id(),new Parameters(watch.searchQuery(),watch.topic(),watch.language(),watch.resultLimit(),watch.sort(),watch.sortOrder(),topicsPublic(watch.repositoryId()))));
            if(refresh){specs.clear();specs.add(new Spec(TaskType.DISCOVER_REPOSITORIES,"repository",Parameters.repository()));}
            if(requested!=null){specs.clear();specs.addAll(requested);}
            Instant deadline=Instant.now().plus(properties.jobTimeout()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            for(var spec:specs) {
                UUID taskId=UUID.randomUUID(), attemptId=UUID.randomUUID();
                String parameters=json.writeValueAsString(spec.parameters());
                Map<String,Object> frozenConfiguration=new TreeMap<>();
                frozenConfiguration.put("repository",target);frozenConfiguration.put("parameters",spec.parameters());
                frozenConfiguration.put("growthEventSettings",spec.type()==TaskType.GROWTH_EVENTS?growthSettings:Map.of());
                if(authorization!=null) frozenConfiguration.put("authorization",authorization);
                String frozen=json.writeValueAsString(frozenConfiguration);
                long revision=revision(spec.type()+spec.key()+frozen);
                Long task=jdbc.queryForObject("INSERT INTO collection_task(public_id,request_id,repository_id,task_type,target_key,parameters,configuration_snapshot,configuration_revision,current_attempt_id) VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?,?) RETURNING id",Long.class,taskId,request,repositoryId,spec.type().name(),spec.key(),parameters,frozen,revision,attemptId);
                if(authorization!=null) jdbc.update("UPDATE collection_task SET authorization_context=?::uuid,credential_ref=? WHERE id=?",authorization.context(),authorization.credentialRef(),task);
                jdbc.update("INSERT INTO collection_attempt(public_id,task_id,attempt_no,node_id) VALUES (?,?,1,?)",attemptId,task,nodeId);
                var envelope=new JobRequest(CollectorProtocol.VERSION,requestId,taskId,attemptId,spec.type(),target,date,revision,spec.parameters(),properties.destinationRef(),deadline,authorization);
                jdbc.update("INSERT INTO collector_dispatch(attempt_id,endpoint,payload,deadline_at) VALUES (?,?,?::jsonb,?)",attemptId,OrchestrationProperties.endpoint(node),json.writeValueAsString(envelope),Timestamp.from(deadline));
            }
            return response(request,requestId,false);
        });
    }
    private void append(long request,long repository,Spec spec){
        if(jdbc.queryForObject("SELECT count(*) FROM collection_task WHERE request_id=? AND task_type=? AND target_key=?",Long.class,request,spec.type().name(),spec.key())>0)return;
        var parent=json.readValue(jdbc.queryForObject("SELECT d.payload::text FROM collection_task t JOIN collector_dispatch d ON d.attempt_id=t.current_attempt_id WHERE t.request_id=? ORDER BY t.id LIMIT 1",String.class,request),JobRequest.class);
        var authority=credentialVault==null?null:credentialVault.scope(repository);var taskId=UUID.randomUUID();var attemptId=UUID.randomUUID();Map<String,Object> configuration=new TreeMap<>();configuration.put("repository",parent.repository());configuration.put("parameters",spec.parameters());if(authority!=null)configuration.put("authorization",authority);Map<String,Boolean> settings=new TreeMap<>();if(spec.type()==TaskType.GROWTH_EVENTS)for(var setting:eventSettings.ensureDefaults(repository))settings.put(setting.eventType(),setting.enabled());configuration.put("growthEventSettings",settings);String frozen=json.writeValueAsString(configuration);long revision=revision(spec.type()+spec.key()+frozen);
        var envelope=new JobRequest(1,parent.requestId(),taskId,attemptId,spec.type(),parent.repository(),parent.businessDate(),revision,spec.parameters(),parent.destinationRef(),Instant.now().plus(properties.jobTimeout()),authority);
        long node=jdbc.queryForObject("SELECT node_id FROM collection_attempt WHERE public_id=?",Long.class,parent.attemptId());
        long task=jdbc.queryForObject("INSERT INTO collection_task(public_id,request_id,repository_id,task_type,target_key,parameters,configuration_snapshot,configuration_revision,current_attempt_id) VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?,?) RETURNING id",Long.class,taskId,request,repository,spec.type().name(),spec.key(),json.writeValueAsString(spec.parameters()),frozen,revision,attemptId);
        if(authority!=null)jdbc.update("UPDATE collection_task SET authorization_context=?::uuid,credential_ref=? WHERE id=?",authority.context(),authority.credentialRef(),task);
        jdbc.update("INSERT INTO collection_attempt(public_id,task_id,attempt_no,node_id) VALUES (?,?,1,?)",attemptId,task,node);
        jdbc.update("INSERT INTO collector_dispatch(attempt_id,endpoint,payload,deadline_at) VALUES (?,(SELECT endpoint FROM collector_node WHERE id=?),?::jsonb,?)",attemptId,node,json.writeValueAsString(envelope),Timestamp.from(envelope.deadlineAt()));
    }
    public void cancelSearch(long query){tx.executeWithoutResult(status->{
        var requests=jdbc.queryForList("SELECT DISTINCT request_id FROM collection_task WHERE task_type='SEARCH_QUERY' AND target_key=? ORDER BY request_id",Long.class,"query:"+query);
        for(long request:requests){jdbc.queryForList("SELECT id FROM collection_request WHERE id=? FOR UPDATE",Long.class,request);var tasks=jdbc.queryForList("SELECT id,current_attempt_id FROM collection_task WHERE request_id=? AND task_type='SEARCH_QUERY' AND target_key=? AND state IN ('READY','ACCEPTED','RUNNING','RETRY') FOR UPDATE",request,"query:"+query);
            for(var task:tasks){jdbc.update("UPDATE collection_task SET state='CANCELLED',completed_at=clock_timestamp() WHERE id=?",task.get("id"));jdbc.update("UPDATE collection_attempt SET state='CANCELLED',completed_at=clock_timestamp() WHERE public_id=?",task.get("current_attempt_id"));jdbc.update("UPDATE collector_dispatch SET state='BLOCKED',last_error='DEFINITION_DELETED',locked_by=NULL,locked_until=NULL WHERE attempt_id=?",task.get("current_attempt_id"));}
            jdbc.update("UPDATE collection_request r SET state='CANCELLED',completed_at=clock_timestamp() WHERE r.id=? AND r.state='ACTIVE' AND NOT EXISTS(SELECT 1 FROM collection_task t WHERE t.request_id=r.id AND t.state IN ('READY','ACCEPTED','RUNNING','RETRY'))",request);
        }
    });}
    private Created response(long id,UUID publicId,boolean coalesced) {
        return new Created(publicId,jdbc.queryForList("SELECT public_id FROM collection_task WHERE request_id=? ORDER BY id",UUID.class,id),coalesced);
    }
    private boolean searchPublic(long id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT public_enabled FROM search_query WHERE id=?",Boolean.class,id));}
    private boolean topicsPublic(long id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT public_topics_enabled FROM repository WHERE id=?",Boolean.class,id));}
    private static long revision(String snapshot) {
        try {return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(snapshot.getBytes(StandardCharsets.UTF_8))).getLong() & Long.MAX_VALUE;}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
