package com.kholodilin.repogrowth.auth.security;

import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class AdminAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final UserAccountService users;

    public AdminAuthorizationManager(UserAccountService users) {
        this.users = users;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        }
        AppUser user = users.findById(principal.userId())
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Unknown user"));
        if (!user.enabled()) {
            throw new AccessDeniedException("Account disabled");
        }
        if (!user.isAdmin()) {
            throw new AccessDeniedException("ADMIN role required");
        }
        return new AuthorizationDecision(true);
    }
}
