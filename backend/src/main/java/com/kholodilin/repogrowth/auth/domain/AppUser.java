package com.kholodilin.repogrowth.auth.domain;

import java.time.Instant;
import java.util.List;

public record AppUser(
        long id,
        String oidcIssuer,
        String oidcSub,
        String displayName,
        boolean enabled,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt,
        List<AppRole> roles
) {
    public boolean hasRole(AppRole role) {
        return roles != null && roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole(AppRole.ADMIN);
    }
}
