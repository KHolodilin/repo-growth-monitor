package com.kholodilin.repogrowth.credentials;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CredentialVaultPropertiesTest {
  @Test
  void rejectsInvalidLeaseAndMissingActiveKey() {
    assertThatThrownBy(
            () -> new CredentialVaultProperties(false, null, Map.of(), Duration.ofHours(25)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cache lease");
    assertThatThrownBy(
            () -> new CredentialVaultProperties(true, "missing", Map.of("other", "x"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("active encryption key");
  }
}
