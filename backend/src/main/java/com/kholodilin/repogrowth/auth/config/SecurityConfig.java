package com.kholodilin.repogrowth.auth.config;

import com.kholodilin.repogrowth.auth.mock.MockAuthenticationProvider;
import com.kholodilin.repogrowth.auth.security.AbsoluteSessionTimeoutFilter;
import com.kholodilin.repogrowth.auth.security.AdminAuthorizationManager;
import com.kholodilin.repogrowth.auth.security.AuthJsonHandlers;
import com.kholodilin.repogrowth.auth.security.MockLoginSuccessHandler;
import com.kholodilin.repogrowth.auth.security.OidcLoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;

@Configuration
@EnableWebSecurity
@Profile("!cli")
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthProvider provider,
            AuthProperties properties,
            AdminAuthorizationManager adminAuthorizationManager,
            AuthJsonHandlers authJsonHandlers,
            MockAuthenticationProvider mockAuthenticationProvider,
            MockLoginSuccessHandler mockLoginSuccessHandler,
            OidcLoginSuccessHandler oidcLoginSuccessHandler,
            Clock clock,
            ClientRegistrationRepository clientRegistrationRepository
    ) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName("_csrf");

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler)
                )
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.migrateSession())
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authJsonHandlers)
                        .accessDeniedHandler(authJsonHandlers)
                )
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/logout"))
                        .logoutSuccessUrl("/")
                        .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                )
                .authorizeHttpRequests(auth -> {
                        if (properties.production()) {
                            auth.requestMatchers(
                                    "/v3/api-docs",
                                    "/v3/api-docs/**",
                                    "/swagger-ui.html",
                                    "/swagger-ui/**"
                            ).denyAll();
                        }
                        auth.requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus",
                                "/error",
                                "/login",
                                "/oauth2/**",
                                "/login/oauth2/**",
                                "/assets/**",
                                "/favicon.ico",
                                "/vite.svg"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/status").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/me").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/v1/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/**").access(adminAuthorizationManager)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/**").access(adminAuthorizationManager)
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/**").access(adminAuthorizationManager)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/**").access(adminAuthorizationManager)
                        .anyRequest().permitAll();
                })
                .addFilterBefore(new AbsoluteSessionTimeoutFilter(properties, clock), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(csrfCookieFilter(), UsernamePasswordAuthenticationFilter.class);

        if (provider == AuthProvider.MOCK) {
            http.authenticationProvider(mockAuthenticationProvider);
            http.formLogin(form -> form
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    .successHandler(mockLoginSuccessHandler)
                    .failureUrl("/login?error")
                    .permitAll()
            );
            http.oauth2Login(oauth -> oauth.disable());
        } else {
            OAuth2AuthorizationRequestResolver resolver = pkceResolver(clientRegistrationRepository);
            http.oauth2Login(oauth -> oauth
                    .loginPage("/login")
                    .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                    .successHandler(oidcLoginSuccessHandler)
            );
            http.formLogin(form -> form.disable());
        }

        // Keep default security headers (incl. cache control); CSRF cookie must be readable by SPA.
        http.headers(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    AuthenticationManager authenticationManager(MockAuthenticationProvider mockAuthenticationProvider) {
        return new ProviderManager(List.of(mockAuthenticationProvider));
    }

    /**
     * Present when AUTH_PROVIDER=mock so SecurityFilterChain injection succeeds without OIDC beans.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(ClientRegistrationRepository.class)
    ClientRegistrationRepository emptyClientRegistrationRepository() {
        return registrationId -> null;
    }

    private static OAuth2AuthorizationRequestResolver pkceResolver(ClientRegistrationRepository repository) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(repository, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }

    /** Forces the CSRF cookie to be written so the SPA can send X-XSRF-TOKEN. */
    private static OncePerRequestFilter csrfCookieFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain filterChain
            ) throws ServletException, IOException {
                CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                if (token != null) {
                    token.getToken();
                }
                filterChain.doFilter(request, response);
            }
        };
    }
}
