package com.kholodilin.repogrowth.auth.security;

import org.springframework.security.core.AuthenticatedPrincipal;

import java.io.Serializable;

public record AppUserPrincipal(long userId, String displayName) implements AuthenticatedPrincipal, Serializable {
    @Override
    public String getName() {
        return displayName;
    }
}
