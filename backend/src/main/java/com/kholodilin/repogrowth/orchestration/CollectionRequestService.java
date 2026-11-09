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
    private Created create(long repositoryId,LocalDate date,String triggerKey,boolean refresh) {
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,repositoryId);
            // Serialize creation without blocking history writers under the request lock.
            var rows=jdbc.queryForList("SELECT r.*,o.login AS owner_login FROM repository r JOIN github_owner o ON o.id=r.owner_id WHERE r.id=?",repositoryId);
            if(rows.isEmpty()) throw ApiException.notFound("Repository not found");
            var repository=rows.getFirst();
            var existing=jdbc.queryForList("SELECT id,public_id FROM collection_request WHERE repository_id=? AND (state='ACTIVE' OR (?::text IS NOT NULL AND trigger_key=?)) ORDER BY (trigger_key=?::text) DESC NULLS LAST,id DESC LIMIT 1",repositoryId,triggerKey,triggerKey,triggerKey);
            if(!existing.isEmpty()) {
                long request=((Number)existing.getFirst().get("id")).longValue();
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
            for(var query:queries.findByRepository(repositoryId)) if(query.enabled()) specs.add(new Spec(TaskType.SEARCH_QUERY,"query:"+query.id(),new Parameters(query.query(),null,null,query.resultLimit(),"stars","desc")));
            for(var watch:watches.findByRepository(repositoryId)) if(watch.enabled()) specs.add(new Spec(TaskType.TOPIC_WATCH,"watch:"+watch.id(),new Parameters(watch.searchQuery(),watch.topic(),watch.language(),watch.resultLimit(),watch.sort(),watch.sortOrder())));
            if(refresh){specs.clear();specs.add(new Spec(TaskType.DISCOVER_REPOSITORIES,"repository",Parameters.repository()));}
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
    private Created response(long id,UUID publicId,boolean coalesced) {
        return new Created(publicId,jdbc.queryForList("SELECT public_id FROM collection_task WHERE request_id=? ORDER BY id",UUID.class,id),coalesced);
    }
    private static long revision(String snapshot) {
        try {return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(snapshot.getBytes(StandardCharsets.UTF_8))).getLong() & Long.MAX_VALUE;}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
