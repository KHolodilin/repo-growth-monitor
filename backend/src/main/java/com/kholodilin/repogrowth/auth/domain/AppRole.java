package com.kholodilin.repogrowth.auth.domain;

public enum AppRole {
    USER,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
