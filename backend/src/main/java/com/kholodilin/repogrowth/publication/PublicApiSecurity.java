package com.kholodilin.repogrowth.publication;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.context.NullSecurityContextRepository;
@Configuration(proxyBeanMethods=false)
public class PublicApiSecurity {
 @Bean @Order(-5) SecurityFilterChain publicReadChain(HttpSecurity http,@org.springframework.beans.factory.annotation.Value("${rgm.public-api.requests-per-minute:120}") long perMinute,@org.springframework.beans.factory.annotation.Value("${rgm.public-api.burst:30}") long burst,@org.springframework.beans.factory.annotation.Value("${rgm.public-api.trusted-proxy-addresses:}") String trustedProxies)throws Exception{
 return http.addFilterBefore(new com.kholodilin.repogrowth.publicapi.PublicApiProtection(perMinute,burst,java.util.Arrays.stream(trustedProxies.split(",")).map(String::trim).filter(s->!s.isEmpty()).collect(java.util.stream.Collectors.toSet())),org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class).securityMatcher("/api/v1/public/**").csrf(c->c.disable()).requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
 .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).securityContext(c->c.securityContextRepository(new NullSecurityContextRepository()))
 .authorizeHttpRequests(c->c.requestMatchers(org.springframework.http.HttpMethod.GET,"/**").permitAll().requestMatchers(org.springframework.http.HttpMethod.HEAD,"/**").permitAll().anyRequest().denyAll()).build();
 }
}
