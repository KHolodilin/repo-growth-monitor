package com.kholodilin.repogrowth.credentials;
import com.kholodilin.repogrowth.orchestration.OrchestrationProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="rgm.credentials.enabled",havingValue="true")
public class CollectorNodeSecurity {
    @Bean @Order(-10)
    SecurityFilterChain credentialResolutionChain(HttpSecurity http,OrchestrationProperties properties) throws Exception {
        return http.securityMatcher("/internal/v1/credentials/resolve","/internal/v1/github-budget/acquire","/internal/v1/github-budget/report","/internal/v1/collector-results").csrf(c->c.disable()).requestCache(c->c.disable())
            .formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
            .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(c->c.securityContextRepository(new NullSecurityContextRepository()))
            .authorizeHttpRequests(c->c.anyRequest().authenticated())
            .addFilterBefore(new NodeFilter(properties),AnonymousAuthenticationFilter.class).build();
    }
    static final class NodeFilter extends OncePerRequestFilter {
        private final OrchestrationProperties properties;
        NodeFilter(OrchestrationProperties properties) {this.properties=properties;}
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
            String header=request.getHeader("Authorization"),node=null;
            byte[] token=(header!=null && header.startsWith("Bearer ")?header.substring(7):"").getBytes(StandardCharsets.UTF_8);
            for(var candidate:properties.nodes()) if(candidate.enabled() && candidate.resolveToken()!=null && candidate.resolveToken().length()>=32 && MessageDigest.isEqual(candidate.resolveToken().getBytes(StandardCharsets.UTF_8),token)) node=candidate.key();
            if(node==null) {response.setStatus(401);response.setHeader("WWW-Authenticate","Bearer");response.setContentType("application/json");response.getWriter().write("{\"code\":\"UNAUTHORIZED\"}");return;}
            var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(node,null,List.of()));SecurityContextHolder.setContext(context);
            try {chain.doFilter(request,response);} finally {SecurityContextHolder.clearContext();}
        }
    }
}
