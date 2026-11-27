package com.kholodilin.repogrowth.collection.integration.github;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Guards the collector-only cutover: the product backend must not ship the legacy in-process GitHub
 * product client. This is not a full ArchUnit suite (those live in user-owned ArchitectureTest
 * files); it only asserts the deleted class stays gone.
 */
class NoProductGitHubClientPresentTest {

  @Test
  void legacyProductGitHubClientClassIsAbsent() {
    assertThatThrownBy(() -> Class.forName("com.kholodilin.repogrowth.collection.integration.github.client.GitHubClient"))
        .isInstanceOf(ClassNotFoundException.class);
  }

  @Test
  void legacyProductGitHubClientConfigClassIsAbsent() {
    assertThatThrownBy(() ->
            Class.forName("com.kholodilin.repogrowth.collection.integration.github.configuration.GitHubClientConfig"))
        .isInstanceOf(ClassNotFoundException.class);
  }
}
