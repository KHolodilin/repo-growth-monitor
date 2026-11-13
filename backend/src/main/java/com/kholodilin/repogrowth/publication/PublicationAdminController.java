package com.kholodilin.repogrowth.publication;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import com.kholodilin.repogrowth.auth.security.AppUserPrincipal;
import com.kholodilin.repogrowth.common.api.ApiException;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
@RestController @RequestMapping("/api/v1/admin")
public class PublicationAdminController {
 private final PublicationService policy;private final JdbcTemplate jdbc;private final JsonMapper json;
 public PublicationAdminController(PublicationService policy,JdbcTemplate jdbc,JsonMapper json){this.policy=policy;this.jdbc=jdbc;this.json=json;}
 @GetMapping("/repositories/{id}/publication") public Object get(@PathVariable long id){var rows=jdbc.queryForList("SELECT (SELECT '/'||o.login||'/'||r.name FROM repository r JOIN github_owner o ON o.id=r.owner_id WHERE r.id=repository.id) AS \"canonicalPath\",public_dashboard_enabled,public_traffic_enabled,public_referrers_enabled,public_paths_enabled,public_topics_enabled,publication_revision,visibility_verified_at FROM repository WHERE id=?",id);if(rows.isEmpty())throw ApiException.notFound("Repository not found");return rows.getFirst();}
 public record Request(Boolean enabled,Boolean traffic,Boolean referrers,Boolean paths,Boolean topics){}
 @PostMapping("/repositories/{id}/publication") @Transactional public Object publish(@PathVariable long id,@RequestBody Request request,Authentication auth,@RequestHeader(value="X-Request-ID",required=false) String requestId){
 if(request.enabled()==null)throw ApiException.validation("enabled is required");jdbc.queryForList("SELECT id FROM repository WHERE id=? FOR UPDATE",id);var before=get(id);policy.publish(id,new PublicationService.Policy(request.enabled(),flag(request.traffic(),before,"public_traffic_enabled"),flag(request.referrers(),before,"public_referrers_enabled"),flag(request.paths(),before,"public_paths_enabled"),flag(request.topics(),before,"public_topics_enabled")));var after=get(id);audit(auth,"REPOSITORY",id,before,after,requestId);return after;
 }
 public record QueryPolicy(Boolean enabled){}
 @PostMapping("/search-queries/{id}/publication") @Transactional public Object query(@PathVariable long id,@RequestBody QueryPolicy request,Authentication auth,@RequestHeader(value="X-Request-ID",required=false) String requestId){
 if(request.enabled()==null)throw ApiException.validation("enabled is required");var rows=jdbc.queryForList("SELECT public_enabled,repository_id FROM search_query WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw ApiException.notFound("Search query not found");var before=rows.getFirst();if(request.enabled()&&!policy.eligible(((Number)before.get("repository_id")).longValue()))throw ApiException.validation("Published parent required");jdbc.update("UPDATE search_query SET public_enabled=? WHERE id=?",request.enabled(),id);var after=Map.of("public_enabled",request.enabled());audit(auth,"QUERY",id,before,after,requestId);return after;
 }
 private boolean flag(Boolean input,Object before,String name){return input==null?Boolean.TRUE.equals(((Map<?,?>)before).get(name)):input;}
 private void audit(Authentication auth,String type,long id,Object before,Object after,String request){if(request!=null&&request.length()>128)throw ApiException.validation("Request ID too long");jdbc.update("INSERT INTO publication_audit(actor_id,entity_type,entity_id,before_policy,after_policy,request_id) VALUES (?,?,?,?::jsonb,?::jsonb,?)",((AppUserPrincipal)auth.getPrincipal()).userId(),type,id,json.writeValueAsString(before),json.writeValueAsString(after),request);}
}
