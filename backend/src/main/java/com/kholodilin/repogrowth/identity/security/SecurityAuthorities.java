package com.kholodilin.repogrowth.identity.security;

import com.kholodilin.repogrowth.identity.model.AppRole;
import com.kholodilin.repogrowth.identity.model.AppUser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class SecurityAuthorities {

  private SecurityAuthorities() {}

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
