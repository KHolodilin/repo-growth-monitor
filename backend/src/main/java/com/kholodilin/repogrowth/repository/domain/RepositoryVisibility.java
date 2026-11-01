package com.kholodilin.repogrowth.repository.domain;

public final class RepositoryVisibility {

    private RepositoryVisibility() {
    }

    public static boolean isPublic(String visibility) {
        return visibility != null && "PUBLIC".equalsIgnoreCase(visibility.trim());
    }
}
