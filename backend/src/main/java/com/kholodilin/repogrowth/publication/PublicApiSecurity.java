package com.kholodilin.repogrowth.publication;

import com.kholodilin.repogrowth.publicapi.PublicApiProtection;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class PublicApiSecurity {
  @Bean
  @Order(-5)
  SecurityFilterChain publicReadChain(
      HttpSecurity http,
      JsonMapper json,
      @Value("${rgm.public-api.requests-per-minute:120}") long perMinute,
      @Value("${rgm.public-api.burst:30}") long burst,
      @Value("${rgm.public-api.trusted-proxy-addresses:}") String trustedProxies)
      throws Exception {
    var trusted =
        Arrays.stream(trustedProxies.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    return http.addFilterBefore(
            new PublicApiProtection(perMinute, burst, trusted, json),
            AnonymousAuthenticationFilter.class)
        .securityMatcher("/api/v1/public/**")
        .csrf(c -> c.disable())
        .requestCache(c -> c.disable())
        .formLogin(c -> c.disable())
        .httpBasic(c -> c.disable())
        .logout(c -> c.disable())
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .securityContext(c -> c.securityContextRepository(new NullSecurityContextRepository()))
        .authorizeHttpRequests(
            c ->
                c.requestMatchers(HttpMethod.GET, "/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.HEAD, "/**")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .build();
  }
}
