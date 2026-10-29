package com.kholodilin.repogrowth.auth.security;

import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserAccess {

    private final UserAccountService users;

    public CurrentUserAccess(UserAccountService users) {
        this.users = users;
    }

    /** Admins may see private/internal repositories; guests and USER may not. */
    public boolean canSeePrivateRepositories() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserPrincipal principal)) {
            return false;
        }
        AppUser user = users.findById(principal.userId()).orElse(null);
        return users.canMutate(user);
    }
}
