package com.kholodilin.repogrowth.publicapi;
import com.kholodilin.repogrowth.publication.*;
import com.kholodilin.repogrowth.common.api.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class PublicPageControllerTest {
 PublicReadService reads;MockMvc mvc;
 @BeforeEach void setup(){reads=mock(PublicReadService.class);mvc=standaloneSetup(new PublicPageController(reads,new ByteArrayResource("<!doctype html>".getBytes()))).build();}
 @Test void servesOnlyResolvedPagesAndKeepsDottedRepositoryNames()throws Exception{when(reads.resolve("/Alpha/project.js")).thenReturn(new PublicationService.Resolved(1,2L,"/Alpha/project.js",false));mvc.perform(get("/Alpha/project.js").accept("text/html")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(content().string("<!doctype html>"));}
 @Test void unknownOrReservedPathsAreNotSoft404s()throws Exception{when(reads.resolve("/Hidden")).thenThrow(ApiException.notFound("Page not found"));for(String path:new String[]{"/Hidden","/api/unknown","/admin/unknown","/favicon.ico"})mvc.perform(get(path).accept("text/html")).andExpect(status().isNotFound());verify(reads,never()).resolve("/api/unknown");}
 @Test void aliasesRedirectPreserveNestedSpaSuffix()throws Exception{
  when(reads.resolve("/Old/project")).thenReturn(new PublicationService.Resolved(1,2L,"/Alpha/new-project",true));
  when(reads.scope("Old","project",PublicationService.Module.PATHS)).thenReturn(java.util.List.of(2L));
  mvc.perform(get("/Old/project/traffic/history?kind=paths&period=7d").accept("text/html"))
   .andExpect(status().isPermanentRedirect())
   .andExpect(header().string("Location","/Alpha/new-project/traffic/history?kind=paths&period=7d"));
  verify(reads).scope("Old","project",PublicationService.Module.PATHS);
 }
 @Test void nestedSpaRoutesRequirePublishedParentAndDefinition()throws Exception{
  when(reads.resolve("/Alpha/project")).thenReturn(new PublicationService.Resolved(1,2L,"/Alpha/project",false));
  when(reads.scope("Alpha","project",PublicationService.Module.REFERRERS)).thenReturn(java.util.List.of(2L));
  mvc.perform(get("/Alpha/project/traffic/history?kind=referrers").accept("text/html")).andExpect(status().isOk());
  doThrow(ApiException.notFound("Page not found")).when(reads).validateDefinition("Alpha","project",false,99L);
  mvc.perform(get("/Alpha/project/search-queries/99").accept("text/html")).andExpect(status().isNotFound());
  when(reads.validateTopic("Alpha","project","spring")).thenReturn(7L);
  mvc.perform(get("/Alpha/project/topics/spring").accept("text/html")).andExpect(status().isOk());
  mvc.perform(get("/Alpha/project/analytics/unsupported").accept("text/html")).andExpect(status().isNotFound());
 }
 @Test void competitorHtmlValidatesDefinitionMembership()throws Exception{
  when(reads.resolve("/Alpha/project")).thenReturn(new PublicationService.Resolved(1,2L,"/Alpha/project",false));
  doNothing().when(reads).validateDefinition("Alpha","project",false,5L);
  when(reads.competitor(false,5L,555L)).thenReturn(java.util.Map.of("githubId","555"));
  mvc.perform(get("/Alpha/project/search-queries/5/competitors/555").accept("text/html")).andExpect(status().isOk());
  doThrow(ApiException.notFound("Page not found")).when(reads).competitor(false,5L,999L);
  mvc.perform(get("/Alpha/project/search-queries/5/competitors/999").accept("text/html")).andExpect(status().isNotFound());
 }
}
