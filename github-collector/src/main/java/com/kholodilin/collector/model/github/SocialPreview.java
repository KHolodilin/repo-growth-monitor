package com.kholodilin.collector.model.github;

/** GraphQL social preview; repositoryJson is opaque metadata when usable. */
public record SocialPreview(boolean usable, String repositoryJson) {
  public static SocialPreview unused() {
    return new SocialPreview(false, null);
  }
}
