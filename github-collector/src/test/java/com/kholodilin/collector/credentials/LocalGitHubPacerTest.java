package com.kholodilin.collector.credentials;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class LocalGitHubPacerTest {
 @Test void limitsBurstAndConcurrencyAndPermitCloseIsIdempotent() {
  var gate=new LocalGitHubPacer();var first=gate.tryAcquire("principal","core").orElseThrow();
  assertThat(gate.tryAcquire("other","search")).isEmpty();first.close();first.close();
  assertThat(gate.tryAcquire("principal","core")).isEmpty();
  var other=gate.tryAcquire("other","search").orElseThrow();
  assertThat(gate.tryAcquire("third","core")).isEmpty();other.close();
 }
}
