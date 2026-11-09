package com.kholodilin.repogrowth.publicapi;
import com.kholodilin.repogrowth.publication.*;
import com.kholodilin.repogrowth.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
/** Resolve publication before returning HTML. Unknown and unpublished paths are real 404s. */
@Controller
public class PublicPageController {
 private final PublicReadService reads;
 private final Resource index;
 @org.springframework.beans.factory.annotation.Autowired public PublicPageController(PublicReadService reads){this(reads,new ClassPathResource("static/index.html"));}
 PublicPageController(PublicReadService reads,Resource index){this.reads=reads;this.index=index;}
 @GetMapping(value={"/{owner}","/{owner}/{repository}","/{owner}/{repository}/analytics/{kind}","/{owner}/{repository}/analytics/{kind}/{definitionId}","/{owner}/{repository}/analytics/{kind}/{definitionId}/competitors/{githubId}"},produces=MediaType.TEXT_HTML_VALUE)
 public ResponseEntity<Resource> page(@PathVariable String owner,@PathVariable(required=false) String repository,@PathVariable(required=false) String kind,@PathVariable(required=false) Long definitionId,@PathVariable(required=false) Long githubId,HttpServletRequest request){
  if(!PublicRoutes.owner(owner)||repository!=null&&!PublicRoutes.repository(repository))return ResponseEntity.notFound().build();
  try {
   var target=reads.resolve("/"+owner+(repository==null?"":"/"+repository));
   String suffix="";
   if(kind!=null){
    if(kind.equals("paths")||kind.equals("referrers")){
     if(definitionId!=null)return ResponseEntity.notFound().build();
     reads.scope(owner,repository,kind.equals("paths")?PublicationService.Module.PATHS:PublicationService.Module.REFERRERS);
    }else if((kind.equals("search")||kind.equals("topics"))&&definitionId!=null){
     reads.validateDefinition(owner,repository,kind.equals("topics"),definitionId);
     if(githubId!=null)reads.competitor(kind.equals("topics"),definitionId,githubId);
    }else return ResponseEntity.notFound().build();
    suffix="/analytics/"+kind+(definitionId==null?"":"/"+definitionId)+(githubId==null?"":"/competitors/"+githubId);
   }
   if(target.redirect()){String query=request.getQueryString();return ResponseEntity.status(308).location(URI.create(target.canonicalPath()+suffix+(query==null?"":"?"+query))).cacheControl(CacheControl.noStore()).build();}
   return index.exists()?ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.TEXT_HTML).body(index):ResponseEntity.notFound().build();
  }catch(ApiException e){return ResponseEntity.notFound().build();}
 }
}
