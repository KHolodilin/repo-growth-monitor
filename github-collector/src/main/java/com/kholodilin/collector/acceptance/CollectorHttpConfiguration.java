package com.kholodilin.collector.acceptance;

import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

@Configuration(proxyBeanMethods=false)
@EnableWebSecurity
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name={"collector.storage.enabled","collector.http.enabled"},havingValue="true")
@EnableConfigurationProperties(CollectorHttpProperties.class)
@Import(CollectorJobController.class)
public class CollectorHttpConfiguration {
    @Bean @Order(0)
    SecurityFilterChain collectorServiceChain(HttpSecurity http,CollectorHttpProperties properties) throws Exception {
        return http.securityMatcher("/internal/v1/**")
            .csrf(c->c.disable()).requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
            .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(c->c.securityContextRepository(new NullSecurityContextRepository()))
            .authorizeHttpRequests(c->c.anyRequest().authenticated())
            .addFilterBefore(new CollectorServiceAuthentication(properties),AnonymousAuthenticationFilter.class).build();
    }
    @Bean @Order(1)
    @ConditionalOnProperty(name="rgm.web.enabled",havingValue="false")
    SecurityFilterChain collectorDenyOtherRoutes(HttpSecurity http) throws Exception {
        return http.csrf(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(c->c.anyRequest().denyAll()).build();
    }
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="collector.credentials.enabled",havingValue="true")
    @Import(com.kholodilin.collector.credentials.CredentialInvalidationController.class)
    static class InvalidationHttp { }
}
