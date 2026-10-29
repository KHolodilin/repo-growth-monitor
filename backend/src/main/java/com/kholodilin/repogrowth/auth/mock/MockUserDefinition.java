package com.kholodilin.repogrowth.auth.mock;

public record MockUserDefinition(
        String username,
        String password,
        String displayName,
        boolean enabled
) {
}
