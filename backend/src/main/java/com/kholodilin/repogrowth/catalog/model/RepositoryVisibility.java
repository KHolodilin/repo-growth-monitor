package com.kholodilin.repogrowth.catalog.model;

public final class RepositoryVisibility {

  private RepositoryVisibility() {}

  public static boolean isPublic(String visibility) {
    return visibility != null && "PUBLIC".equalsIgnoreCase(visibility.trim());
  }
}
