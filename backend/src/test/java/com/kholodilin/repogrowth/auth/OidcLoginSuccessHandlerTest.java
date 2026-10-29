package com.kholodilin.repogrowth.auth;

import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.config.AuthProperties;
import com.kholodilin.repogrowth.auth.config.AuthProvider;
import com.kholodilin.repogrowth.auth.domain.AppRole;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import com.kholodilin.repogrowth.auth.security.AppUserPrincipal;
import com.kholodilin.repogrowth.auth.security.OidcLoginSuccessHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OidcLoginSuccessHandlerTest {

    @Mock
    UserAccountService users;

    @Test
    void replacesPrincipalAndRedirectsHome() throws Exception {
        AuthProperties properties = new AuthProperties(
                "zitadel",
                false,
                Duration.ofHours(8),
                Duration.ofHours(24),
                new AuthProperties.Oidc("https://idp.example", "c", "s", null, null),
                null
        );
        OidcLoginSuccessHandler handler = new OidcLoginSuccessHandler(
                users,
                AuthProvider.ZITADEL,
                properties,
                Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC)
        );
        AppUser stored = new AppUser(
                7L, "https://idp.example", "sub-7", "Ada", true,
                Instant.parse("2026-01-02T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"),
                List.of(AppRole.USER, AppRole.ADMIN)
        );
        when(users.ensureOnLogin(eq("https://idp.example"), eq("sub-7"), anyString())).thenReturn(stored);

        OidcUser oidcUser = oidcUser("sub-7", "https://idp.example", "Ada Lovelace", null, null);
        Authentication authentication = new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "oidc");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isInstanceOf(AppUserPrincipal.class);
        AppUserPrincipal principal = (AppUserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.userId()).isEqualTo(7L);
        assertThat(principal.getName()).isEqualTo("Ada");
        SecurityContextHolder.clearContext();
    }

    @Test
    void fallsBackToEmailAndConfiguredIssuer() throws Exception {
        AuthProperties properties = new AuthProperties("zitadel", false, null, null,
                new AuthProperties.Oidc("https://configured.example", "c", "s", null, null), null);
        OidcLoginSuccessHandler handler = new OidcLoginSuccessHandler(
                users, AuthProvider.ZITADEL, properties, Clock.systemUTC());
        AppUser stored = new AppUser(
                5L, "https://configured.example", "sub-5", "mail@ex.com", true,
                Instant.now(), Instant.now(), Instant.now(), List.of(AppRole.USER));
        when(users.ensureOnLogin(eq("https://configured.example"), eq("sub-5"), eq("mail@ex.com"))).thenReturn(stored);
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", "sub-5");
        claims.put("email", "mail@ex.com");
        Instant now = Instant.now();
        OidcIdToken token = new OidcIdToken("t", now, now.plusSeconds(60), claims);
        OidcUser oidcUser = new DefaultOidcUser(List.of(new OidcUserAuthority(token)), token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "oidc"));
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        SecurityContextHolder.clearContext();
    }

    @Test
    void fallsBackToPreferredUsernameAndPassesThroughNonOidc() throws Exception {
        AuthProperties properties = new AuthProperties("zitadel", false, null, null,
                new AuthProperties.Oidc("https://idp.example", "c", "s", null, null), null);
        OidcLoginSuccessHandler handler = new OidcLoginSuccessHandler(
                users, AuthProvider.ZITADEL, properties, Clock.systemUTC());
        AppUser stored = new AppUser(
                4L, "https://idp.example", "sub-4", "pref", true,
                Instant.now(), Instant.now(), Instant.now(), List.of(AppRole.USER));
        when(users.ensureOnLogin(eq("https://idp.example"), eq("sub-4"), eq("pref"))).thenReturn(stored);
        OidcUser oidcUser = oidcUser("sub-4", "https://idp.example", null, "pref", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "oidc"));
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        SecurityContextHolder.clearContext();

        MockHttpServletResponse passthrough = new MockHttpServletResponse();
        Authentication plain = UsernamePasswordAuthenticationToken.authenticated("x", null, List.of());
        handler.onAuthenticationSuccess(new MockHttpServletRequest("GET", "/"), passthrough, plain);
    }

    @Test
    void disablesRedirectWhenAccountDisabled() throws Exception {
        AuthProperties properties = new AuthProperties("zitadel", false, null, null,
                new AuthProperties.Oidc("https://idp.example", "c", "s", null, null), null);
        OidcLoginSuccessHandler handler = new OidcLoginSuccessHandler(
                users, AuthProvider.ZITADEL, properties, Clock.systemUTC());
        AppUser disabled = new AppUser(
                3L, "https://idp.example", "sub-3", "Blocked", false,
                null, Instant.now(), Instant.now(), List.of(AppRole.USER)
        );
        when(users.ensureOnLogin(anyString(), eq("sub-3"), anyString())).thenReturn(disabled);
        OidcUser oidcUser = oidcUser("sub-3", "https://idp.example", null, "blocked", "b@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "oidc"));

        assertThat(response.getRedirectedUrl()).contains("error=disabled");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void fallsBackToSubjectAndRegistrationIdWithoutSessionOnDisable() throws Exception {
        AuthProperties properties = new AuthProperties("zitadel", false, null, null,
                new AuthProperties.Oidc("  ", "c", "s", null, null), null);
        OidcLoginSuccessHandler handler = new OidcLoginSuccessHandler(
                users, AuthProvider.ZITADEL, properties, Clock.systemUTC());
        AppUser disabled = new AppUser(
                8L, "oidc", "only-sub", "only-sub", false,
                null, Instant.now(), Instant.now(), List.of(AppRole.USER));
        when(users.ensureOnLogin(eq("oidc"), eq("only-sub"), eq("only-sub"))).thenReturn(disabled);
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", "only-sub");
        Instant now = Instant.now();
        OidcIdToken token = new OidcIdToken("t", now, now.plusSeconds(60), claims);
        OidcUser oidcUser = new DefaultOidcUser(List.of(new OidcUserAuthority(token)), token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "oidc"));
        assertThat(response.getRedirectedUrl()).contains("error=disabled");
        SecurityContextHolder.clearContext();
    }

    @Test
    void fallsBackToProviderLabelWhenNotOAuth2Token() throws Exception {
        AuthProperties properties = new AuthProperties("keycloak", false, null, null,
                new AuthProperties.Oidc(null, "c", "s", null, null), null);
        OidcLoginSuccessHandler handler = new OidcLoginSuccessHandler(
                users, AuthProvider.KEYCLOAK, properties, Clock.systemUTC());
        AppUser stored = new AppUser(
                6L, "keycloak", "sub-6", "sub-6", true,
                Instant.now(), Instant.now(), Instant.now(), List.of(AppRole.USER));
        when(users.ensureOnLogin(eq("keycloak"), eq("sub-6"), eq("sub-6"))).thenReturn(stored);
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", "sub-6");
        Instant now = Instant.now();
        OidcIdToken token = new OidcIdToken("t", now, now.plusSeconds(60), claims);
        OidcUser oidcUser = new DefaultOidcUser(List.of(new OidcUserAuthority(token)), token);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                oidcUser, null, oidcUser.getAuthorities());
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        SecurityContextHolder.clearContext();
    }

    private static OidcUser oidcUser(String sub, String issuer, String name, String preferred, String email) {
        Instant now = Instant.now();
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", sub);
        claims.put("iss", issuer);
        if (name != null) {
            claims.put("name", name);
        }
        if (preferred != null) {
            claims.put("preferred_username", preferred);
        }
        if (email != null) {
            claims.put("email", email);
        }
        OidcIdToken token = new OidcIdToken("token", now, now.plusSeconds(3600), claims);
        return new DefaultOidcUser(List.of(new OidcUserAuthority(token)), token);
    }
}
