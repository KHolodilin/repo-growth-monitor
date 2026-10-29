package com.kholodilin.repogrowth.auth.mock;

import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.config.AuthProvider;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import com.kholodilin.repogrowth.auth.security.AppUserPrincipal;
import com.kholodilin.repogrowth.auth.security.SecurityAuthorities;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

@Component
public class MockAuthenticationProvider implements AuthenticationProvider {

    private final AuthProvider provider;
    private final MockUserCatalog catalog;
    private final UserAccountService users;

    public MockAuthenticationProvider(AuthProvider provider, MockUserCatalog catalog, UserAccountService users) {
        this.provider = provider;
        this.catalog = catalog;
        this.users = users;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (provider != AuthProvider.MOCK) {
            return null;
        }
        String username = authentication.getName();
        Object credentials = authentication.getCredentials();
        String password = credentials == null ? "" : credentials.toString();
        MockUserDefinition definition = catalog.find(username)
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));
        if (definition.password() == null || !definition.password().equals(password)) {
            throw new BadCredentialsException("Invalid username or password");
        }
        if (!definition.enabled()) {
            throw new DisabledException("Account disabled");
        }
        AppUser user = catalog.requireSynced(username);
        if (!user.enabled()) {
            throw new DisabledException("Account disabled");
        }
        users.ensureOnLogin(MockUserCatalog.ISSUER, user.oidcSub(), user.displayName());
        AppUser refreshed = users.findById(user.id()).orElse(user);
        AppUserPrincipal principal = new AppUserPrincipal(refreshed.id(), refreshed.displayName());
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                SecurityAuthorities.from(refreshed)
        );
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return provider == AuthProvider.MOCK
                && UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
