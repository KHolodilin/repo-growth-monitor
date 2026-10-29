package com.kholodilin.repogrowth.auth.security;

import com.kholodilin.repogrowth.auth.domain.AppRole;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class SecurityAuthorities {

    private SecurityAuthorities() {
    }

    public static Collection<? extends GrantedAuthority> from(AppUser user) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (user == null || user.roles() == null) {
            return authorities;
        }
        for (AppRole role : user.roles()) {
            authorities.add(new SimpleGrantedAuthority(role.authority()));
        }
        return authorities;
    }
}
