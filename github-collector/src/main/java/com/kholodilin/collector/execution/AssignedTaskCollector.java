package com.kholodilin.collector.execution;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.queue.JobQueue.Lease;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Produces observations only. Product interpretation/history never enters the Collector module. */
public final class AssignedTaskCollector {
    private final SourceReader source;private final ExecutionProperties properties;private final JsonMapper json=JsonMapper.builder().build();
    public AssignedTaskCollector(SourceReader source,ExecutionProperties properties){this.source=source;this.properties=properties;}
    public static final class Incomplete extends RuntimeException {public Incomplete(){super("Incomplete GitHub observation");}}
    private String part(String value){return UriUtils.encodePathSegment(value,StandardCharsets.UTF_8);}
    private JsonNode get(Lease lease,JobRequest job,String key,String path,boolean optional){return source.read(lease,job,key,"GET",path,null,optional);}
    private void keep(Map<String,String> metadata,String key,JsonNode value){if(value!=null && !value.isNull() && !value.isMissingNode())metadata.put(key,json.writeValueAsString(value));}
    private long number(JsonNode node,String field){if(node==null || !node.hasNonNull(field) || !node.get(field).isIntegralNumber() || node.get(field).asLong()<0)throw new Incomplete();return node.get(field).asLong();}
    private List<JsonNode> paged(Lease lease,JobRequest job,String key,String path){
        List<JsonNode> items=new ArrayList<>();
        for(int page=1;page<=properties.maximumPages();page++){
            JsonNode data=get(lease,job,key+":"+page,path+(path.contains("?")?"&":"?")+"per_page=100&page="+page,false);
            if(!data.isArray())throw new Incomplete();data.forEach(items::add);if(data.size()<100)return items;
        }
        return items;
    }
    public Snapshot collect(Lease lease,JobRequest job){
        String repo="/repos/"+part(job.repository().owner())+"/"+part(job.repository().name());
        Map<String,String> metadata=new TreeMap<>();metadata.put("sourceWindowPages",Integer.toString(properties.maximumPages()));Map<Metric,Long> metrics=new EnumMap<>(Metric.class);
        List<String> contributorLogins=null;List<TrafficDay> traffic=new ArrayList<>();List<SourceObservation> sources=new ArrayList<>();List<RankedRepository> rankings=new ArrayList<>();Long total=null;Integer position=null;
        switch(job.type()){
            case STATS,GROWTH_EVENTS -> {
                JsonNode remote=get(lease,job,"repository",repo,false);keep(metadata,"repository",remote);
                metrics.put(Metric.STARS,number(remote,"stargazers_count"));metrics.put(Metric.FORKS,number(remote,"forks_count"));
                if(remote.hasNonNull("subscribers_count"))metrics.put(Metric.WATCHERS,number(remote,"subscribers_count"));
                var contributors=paged(lease,job,"contributors",repo+"/contributors?anon=true");
                contributorLogins=contributors.stream().filter(c->c.hasNonNull("login") && !c.get("login").asText().isBlank()).map(c->c.get("login").asText().trim()).distinct().sorted().toList();
                JsonNode count=get(lease,job,"contributor-count",repo+"/contributors?per_page=1&anon=true",false);
                metrics.put(Metric.CONTRIBUTORS,number(count,"total"));metadata.put("contributors",json.writeValueAsString(contributors));
                if(contributors.size()>=properties.maximumPages()*100)contributorLogins=null;
                JsonNode readme=get(lease,job,"readme",repo+"/readme",true);keep(metadata,"readme",readme);
                String graphBody=json.writeValueAsString(Map.of("query","query($owner:String!,$name:String!){repository(owner:$owner,name:$name){usesCustomOpenGraphImage openGraphImageUrl}}","variables",Map.of("owner",job.repository().owner(),"name",job.repository().name())));
                JsonNode preview=source.read(lease,job,"social-preview","POST","/graphql",graphBody,true);
                if(!preview.hasNonNull("errors"))keep(metadata,"socialPreview",preview.path("data").path("repository"));
                if(job.type()==TaskType.STATS){
                    keep(metadata,"community",get(lease,job,"community",repo+"/community/profile",true));
                    keep(metadata,"latestCommits",get(lease,job,"latest-commit",repo+"/commits?per_page=1",true));
                    keep(metadata,"latestRelease",get(lease,job,"latest-release",repo+"/releases/latest",true));metadata.put("releaseFetched","true");
                    boolean security=false;for(String path:List.of(".github/SECURITY.md","SECURITY.md","docs/SECURITY.md")){if(!get(lease,job,"security:"+path,repo+"/contents/"+path,true).isNull()){security=true;break;}}
                    metadata.put("securityPolicy",Boolean.toString(security));
                    boolean issueTemplate=false;for(String path:List.of(".github/ISSUE_TEMPLATE",".github/ISSUE_TEMPLATE.md","ISSUE_TEMPLATE.md","docs/ISSUE_TEMPLATE.md")){if(!get(lease,job,"issue-template:"+path,repo+"/contents/"+path,true).isNull()){issueTemplate=true;break;}}
                    metadata.put("issueTemplate",Boolean.toString(issueTemplate));
                }else{
                    metadata.put("issues",json.writeValueAsString(paged(lease,job,"issues",repo+"/issues?state=all&sort=created&direction=desc")));
                    metadata.put("pulls",json.writeValueAsString(paged(lease,job,"pulls",repo+"/pulls?state=all&sort=created&direction=desc")));
                    metadata.put("releases",json.writeValueAsString(paged(lease,job,"releases",repo+"/releases")));
                    if(readme.hasNonNull("name"))keep(metadata,"readmeCommits",get(lease,job,"readme-commit",repo+"/commits?per_page=1&path="+URLEncoder.encode(readme.get("name").asText(),StandardCharsets.UTF_8),true));
                }
            }
            case DISCOVER_REPOSITORIES -> {
                List<JsonNode> repositories=paged(lease,job,"accessible-repositories","/user/repos?affiliation=owner,collaborator,organization_member&sort=full_name");
                if(repositories.size()>=properties.maximumPages()*100)throw new Incomplete();
                keep(metadata,"repositories",json.valueToTree(repositories.stream().filter(item->item.path("owner").path("login").asText().equalsIgnoreCase(job.repository().owner())).toList()));
            }
            case TRAFFIC -> {
                JsonNode views=get(lease,job,"views",repo+"/traffic/views",false),clones=get(lease,job,"clones",repo+"/traffic/clones",false);
                Map<LocalDate,Long[]> days=new TreeMap<>();
                for(JsonNode view:views.path("views")){LocalDate date=Instant.parse(view.get("timestamp").asText()).atZone(ZoneOffset.UTC).toLocalDate();Long[] d=days.computeIfAbsent(date,k->new Long[4]);d[0]=number(view,"count");d[1]=number(view,"uniques");}
                for(JsonNode clone:clones.path("clones")){LocalDate date=Instant.parse(clone.get("timestamp").asText()).atZone(ZoneOffset.UTC).toLocalDate();Long[] d=days.computeIfAbsent(date,k->new Long[4]);d[2]=number(clone,"count");d[3]=number(clone,"uniques");}
                days.forEach((date,d)->traffic.add(new TrafficDay(date,d[0],d[1],d[2],d[3])));
            }
            case REFERRERS,POPULAR_PATHS -> {
                boolean paths=job.type()==TaskType.POPULAR_PATHS;JsonNode data=get(lease,job,"sources",repo+"/traffic/popular/"+(paths?"paths":"referrers"),false);
                if(!data.isArray())throw new Incomplete();
                for(JsonNode row:data)sources.add(new SourceObservation(row.get(paths?"path":"referrer").asText(),row.hasNonNull("title")?row.get("title").asText():null,number(row,"count"),number(row,"uniques")));
            }
            case SEARCH_QUERY,TOPIC_WATCH -> {
                int limit=job.parameters().resultLimit()==null?50:job.parameters().resultLimit();String query=job.parameters().query();
                String path="/search/repositories?q="+URLEncoder.encode(query,StandardCharsets.UTF_8)+"&per_page="+limit+"&sort="+URLEncoder.encode(Objects.requireNonNullElse(job.parameters().sort(),"stars"),StandardCharsets.UTF_8)+"&order="+Objects.requireNonNullElse(job.parameters().order(),"desc");
                JsonNode data=get(lease,job,"search",path,false);total=number(data,"total_count");
                position=findPosition(data.path("items"),job.repository().githubId());
                if(position==null){
                    if(data.path("incomplete_results").asBoolean(false) || data.path("items").size()<Math.min(limit,total)){source.invalidate(lease,"search");throw new Incomplete();}
                    data=get(lease,job,"search-confirmation",path,false);total=number(data,"total_count");position=findPosition(data.path("items"),job.repository().githubId());
                    if(position==null && (data.path("incomplete_results").asBoolean(false) || data.path("items").size()<Math.min(limit,total))){source.invalidate(lease,"search-confirmation");throw new Incomplete();}
                }
                int rank=1;for(JsonNode row:data.path("items"))rankings.add(new RankedRepository(row.get("id").asText(),row.get("full_name").asText(),rank++,number(row,"stargazers_count"),number(row,"forks_count")));
                keep(metadata,"search",data);metadata.put("enrichmentStatus","DEFERRED");
            }
        }
        return new Snapshot(source.observedAt(lease),true,metrics,contributorLogins,traffic,sources,rankings,total,position,metadata,List.of());
    }
    private Integer findPosition(JsonNode items,String id){int position=1;for(JsonNode item:items){if(item.path("id").asText().equals(id))return position;position++;}return null;}
}
