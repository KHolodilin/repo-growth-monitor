package com.kholodilin.repogrowth.publication;
import java.util.*;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.kholodilin.repogrowth.common.api.ApiException;
@Service @EnableConfigurationProperties(PublicationProperties.class)
public class PublicationService {
 public record Policy(boolean dashboard,boolean traffic,boolean referrers,boolean paths,boolean topics){}
 public record Resolved(long ownerId,Long repositoryId,String canonicalPath,boolean redirect){}
 private final JdbcTemplate jdbc;private final PublicationProperties properties;
 public PublicationService(JdbcTemplate jdbc,PublicationProperties properties){this.jdbc=jdbc;this.properties=properties;}
 private String eligible(){return "r.public_dashboard_enabled AND r.visibility='PUBLIC' AND r.visibility_verified_at<=clock_timestamp() AND r.visibility_verified_at>clock_timestamp()-(?*interval '1 millisecond')";}
 @Transactional public void publish(long repository,Policy policy){
 var rows=jdbc.queryForList("SELECT o.login,r.visibility,r.visibility_verified_at FROM repository r JOIN github_owner o ON o.id=r.owner_id WHERE r.id=? FOR UPDATE OF r",repository);
 if(rows.isEmpty())throw ApiException.notFound("Repository not found");if(policy.dashboard()&&!PublicRoutes.owner((String)rows.getFirst().get("login")))throw ApiException.validation("Owner conflicts with reserved public namespace");
 if(policy.dashboard()){var row=rows.getFirst();var checked=(java.sql.Timestamp)row.get("visibility_verified_at");if(!"PUBLIC".equals(row.get("visibility"))||checked==null||checked.toInstant().plus(properties.verificationMaxAge()).isBefore(Instant.now())||checked.toInstant().isAfter(Instant.now()))throw ApiException.validation("Verified public visibility is required before publication");}
 jdbc.update("UPDATE repository SET public_dashboard_enabled=?,public_traffic_enabled=?,public_referrers_enabled=?,public_paths_enabled=?,public_topics_enabled=? WHERE id=?",policy.dashboard(),policy.traffic(),policy.referrers(),policy.paths(),policy.topics(),repository);
 }
 public boolean eligible(long id){var names=jdbc.queryForList("SELECT o.login FROM repository r JOIN github_owner o ON o.id=r.owner_id WHERE r.id=? AND "+eligible(),String.class,id,properties.verificationMaxAge().toMillis());return !names.isEmpty()&&PublicRoutes.owner(names.getFirst());}
 public enum Module { DASHBOARD,TRAFFIC,REFERRERS,PATHS,TOPICS }
 public boolean allows(long id,Module module){String flag=switch(module){case DASHBOARD->"public_dashboard_enabled";case TRAFFIC->"public_traffic_enabled";case REFERRERS->"public_referrers_enabled";case PATHS->"public_paths_enabled";case TOPICS->"public_topics_enabled";};var names=jdbc.queryForList("SELECT o.login FROM repository r JOIN github_owner o ON o.id=r.owner_id WHERE r.id=? AND r."+flag+" AND "+eligible(),String.class,id,properties.verificationMaxAge().toMillis());return !names.isEmpty()&&PublicRoutes.owner(names.getFirst());}
 public boolean queryEligible(long query){var names=jdbc.queryForList("SELECT o.login FROM search_query q JOIN repository r ON r.id=q.repository_id JOIN github_owner o ON o.id=r.owner_id WHERE q.id=? AND q.public_enabled AND "+eligible(),String.class,query,properties.verificationMaxAge().toMillis());return !names.isEmpty()&&PublicRoutes.owner(names.getFirst());}
 @org.springframework.beans.factory.annotation.Autowired private com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository owners;
 @Transactional public void metadata(long id,com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse remote,Instant at){
 if(!verify(id,remote.id(),remote.resolvedVisibility(),at))return;
 if(remote.owner()!=null){var owner=remote.owner();var stored=owners.upsert(owner.id(),owner.login(),"Organization".equalsIgnoreCase(owner.type())?com.kholodilin.repogrowth.repository.domain.OwnerType.ORGANIZATION:com.kholodilin.repogrowth.repository.domain.OwnerType.USER,null,null);jdbc.update("UPDATE repository SET owner_id=?,name=? WHERE id=? AND github_id=?",stored.id(),remote.name(),id,remote.id());}
 }
 @Transactional public boolean verify(long id,long githubId,String visibility,Instant observedAt){
 if(!Set.of("PUBLIC","PRIVATE","INTERNAL","DELETED").contains(visibility)||observedAt==null||observedAt.isAfter(Instant.now().plusSeconds(5)))throw ApiException.validation("Invalid metadata verification");
 return jdbc.update("UPDATE repository SET visibility=?,visibility_verified_at=? WHERE id=? AND github_id=? AND (visibility_verified_at IS NULL OR visibility_verified_at<=?)",visibility,java.sql.Timestamp.from(observedAt),id,githubId,java.sql.Timestamp.from(observedAt))==1;
 }
 public Resolved resolve(String path){
 if(path==null||!path.startsWith("/"))throw missing();String clean=path.endsWith("/")?path.substring(0,path.length()-1):path;String[] parts=clean.substring(1).split("/",-1);
 if(parts.length<1||parts.length>2||!PublicRoutes.owner(parts[0])||(parts.length==2&&!PublicRoutes.repository(parts[1])))throw missing();
 var owners=jdbc.queryForList("SELECT id,login FROM github_owner WHERE lower(login)=lower(?)",parts[0]);
 boolean currentOwner=!owners.isEmpty();if(!currentOwner)owners=jdbc.queryForList("SELECT o.id,o.login FROM github_owner_alias a JOIN github_owner o ON o.id=a.owner_id WHERE lower(a.login)=lower(?)",parts[0]);
 if(parts.length==1){if(owners.isEmpty())throw missing();var owner=owners.getFirst();long id=((Number)owner.get("id")).longValue();if(!PublicRoutes.owner((String)owner.get("login"))||jdbc.queryForObject("SELECT count(*) FROM repository r WHERE r.owner_id=? AND "+eligible(),Long.class,id,properties.verificationMaxAge().toMillis())==0)throw missing();String canonical="/"+owner.get("login");return new Resolved(id,null,canonical,!path.equals(canonical));}
 List<Long> ids=currentOwner?jdbc.queryForList("SELECT id FROM repository WHERE owner_id=? AND lower(name)=lower(?)",Long.class,owners.getFirst().get("id"),parts[1]):List.of();
 // A current canonical entity wins even when unpublished: never fall through to a public alias.
 if(ids.isEmpty())ids=jdbc.queryForList("SELECT repository_id FROM repository_path_alias WHERE lower(owner_login)=lower(?) AND lower(repository_name)=lower(?)",Long.class,parts[0],parts[1]);
 if(ids.isEmpty()&&!currentOwner&&!owners.isEmpty())ids=jdbc.queryForList("SELECT id FROM repository WHERE owner_id=? AND lower(name)=lower(?)",Long.class,owners.getFirst().get("id"),parts[1]);
 if(ids.isEmpty()&&!currentOwner&&!owners.isEmpty())ids=jdbc.queryForList("SELECT DISTINCT r.id FROM repository_path_alias a JOIN repository r ON r.id=a.repository_id WHERE r.owner_id=? AND lower(a.repository_name)=lower(?)",Long.class,owners.getFirst().get("id"),parts[1]);
 if(ids.size()!=1||!eligible(ids.getFirst()))throw missing();var target=jdbc.queryForMap("SELECT r.owner_id,o.login,r.name FROM repository r JOIN github_owner o ON o.id=r.owner_id WHERE r.id=?",ids.getFirst());String canonical="/"+target.get("login")+"/"+target.get("name");return new Resolved(((Number)target.get("owner_id")).longValue(),ids.getFirst(),canonical,!path.equals(canonical));
 }
 private ApiException missing(){return ApiException.notFound("Page not found");}
}
