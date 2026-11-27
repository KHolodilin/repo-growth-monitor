package com.kholodilin.repogrowth.identity.model;

public enum AppRole {
  USER,
  ADMIN;

  public String authority() {
    return "ROLE_" + name();
  }
}
