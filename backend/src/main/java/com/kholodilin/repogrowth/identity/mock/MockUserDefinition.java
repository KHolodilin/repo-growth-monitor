package com.kholodilin.repogrowth.identity.mock;

public record MockUserDefinition(
    String username, String password, String displayName, boolean enabled) {}
