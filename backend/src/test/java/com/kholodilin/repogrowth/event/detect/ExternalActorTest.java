package com.kholodilin.repogrowth.event.detect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalActorTest {

    @Test
    void ownerIsNotExternal() {
        assertThat(ExternalActor.isExternal("acme", "User", "acme")).isFalse();
        assertThat(ExternalActor.isExternal("Acme", "User", "acme")).isFalse();
    }

    @Test
    void botAndAppAreNotExternal() {
        assertThat(ExternalActor.isExternal("helper", "Bot", "acme")).isFalse();
        assertThat(ExternalActor.isExternal("helper", "App", "acme")).isFalse();
        assertThat(ExternalActor.isExternal("dependabot[bot]", "User", "acme")).isFalse();
    }

    @Test
    void otherUserIsExternal() {
        assertThat(ExternalActor.isExternal("alice", "User", "acme")).isTrue();
    }
}
