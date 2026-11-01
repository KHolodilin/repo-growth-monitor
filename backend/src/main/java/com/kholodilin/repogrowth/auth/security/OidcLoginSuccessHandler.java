package com.kholodilin.repogrowth.auth.security;

import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.config.AuthProperties;
import com.kholodilin.repogrowth.auth.config.AuthProvider;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

@Component
public class OidcLoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final UserAccountService users;
    private final AuthProvider provider;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public OidcLoginSuccessHandler(
            UserAccountService users,
            AuthProvider provider,
            AuthProperties properties,
            Clock clock
    ) {
        this.users = users;
        this.provider = provider;
        this.properties = properties;
        this.clock = clock;
        setDefaultTargetUrl("/");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws ServletException, IOException {
        if (!(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            super.onAuthenticationSuccess(request, response, authentication);
            return;
        }
        String issuer = resolveIssuer(authentication, oidcUser);
        String displayName = resolveDisplayName(oidcUser);
        AppUser user = users.ensureOnLogin(issuer, oidcUser.getSubject(), displayName);
        if (!user.enabled()) {
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            response.sendRedirect("/login?error=disabled");
            return;
        }
        AppUserPrincipal principal = new AppUserPrincipal(user.id(), user.displayName());
        Authentication replaced = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                SecurityAuthorities.from(user)
        );
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(replaced);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        HttpSession session = request.getSession(true);
        session.setAttribute(AbsoluteSessionTimeoutFilter.CREATED_AT_ATTR, Instant.now(clock));
        super.onAuthenticationSuccess(request, response, replaced);
    }

    private String resolveIssuer(Authentication authentication, OidcUser oidcUser) {
        if (oidcUser.getIssuer() != null) {
            return oidcUser.getIssuer().toString();
        }
        if (properties.oidc().issuerUri() != null && !properties.oidc().issuerUri().isBlank()) {
            return properties.oidc().issuerUri();
        }
        if (authentication instanceof OAuth2AuthenticationToken token) {
            return token.getAuthorizedClientRegistrationId();
        }
        return provider.issuerLabel();
    }

    private static String resolveDisplayName(OidcUser oidcUser) {
        if (oidcUser.getFullName() != null && !oidcUser.getFullName().isBlank()) {
            return oidcUser.getFullName();
        }
        if (oidcUser.getPreferredUsername() != null && !oidcUser.getPreferredUsername().isBlank()) {
            return oidcUser.getPreferredUsername();
        }
        if (oidcUser.getEmail() != null && !oidcUser.getEmail().isBlank()) {
            return oidcUser.getEmail();
        }
        return oidcUser.getSubject();
    }
}
