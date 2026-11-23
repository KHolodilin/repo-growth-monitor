package com.kholodilin.repogrowth.orchestration.idempotency;

import static org.assertj.core.api.Assertions.*;

import com.kholodilin.idempotency.jackson.CanonicalJsonFingerprintStrategy;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScopedIdempotencyTest {
  @Test
  void fingerprintsMatchCanonicalListOrder() {
    assertThat(ScopedIdempotency.key("user:42", "abc"))
        .isEqualTo(new CanonicalJsonFingerprintStrategy().calculate(List.of("user:42", "abc")));
  }

  @Test
  void rejectsBlankScopeAndInvalidKeys() {
    assertThatThrownBy(() -> ScopedIdempotency.key(" ", "abc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Trusted scope is required");
    assertThatThrownBy(() -> ScopedIdempotency.key("user:1", null))
        .isInstanceOf(InvalidIdempotencyKeyException.class);
    assertThatThrownBy(() -> ScopedIdempotency.key("user:1", "bad key"))
        .isInstanceOf(InvalidIdempotencyKeyException.class)
        .hasMessage("Idempotency-Key must contain 1..128 ASCII token characters");
  }
}
