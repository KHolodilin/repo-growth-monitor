package com.kholodilin.repogrowth.identity.security;

import java.io.Serializable;
import org.springframework.security.core.AuthenticatedPrincipal;

public record AppUserPrincipal(long userId, String displayName)
    implements AuthenticatedPrincipal, Serializable {
  @Override
  public String getName() {
    return displayName;
  }
}
