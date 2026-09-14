package com.kholodilin.repogrowth.traffic;

import com.kholodilin.repogrowth.common.config.TrafficProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServicePathClassifierTest {

    private final ServicePathClassifier classifier = new ServicePathClassifier(new TrafficProperties(null));

    @ParameterizedTest
    @ValueSource(strings = {
            "/acme/demo/graphs/traffic",
            "/acme/demo/pulse",
            "/acme/demo/issues",
            "/acme/demo/issues/50",
            "/acme/demo/pull/51/files",
            "/acme/demo/settings/secrets/actions",
    })
    void recognizesRepositoryTabs(String path) {
        assertThat(classifier.isServicePath(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/acme/demo",
            "/acme/demo/",
            "/acme/demo/blob/main/README.md",
            "/acme/demo/tree/main/docs",
            "/acme",
            "/",
    })
    void leavesLandingPagesAlone(String path) {
        assertThat(classifier.isServicePath(path)).isFalse();
    }

    @Test
    void ignoresTheQueryStringAndLetterCase() {
        assertThat(classifier.isServicePath("/acme/demo/Issues?q=is%3Aopen")).isTrue();
        assertThat(classifier.isServicePath("/acme/demo?tab=readme-ov-file")).isFalse();
    }

    /**
     * A repository can own a branch or a directory named like a tab, but GitHub still serves those
     * under /blob or /tree, so only the configured segment right after the repository counts.
     */
    @Test
    void onlyLooksAtTheSegmentAfterTheRepository() {
        assertThat(classifier.isServicePath("/acme/demo/tree/main/issues")).isFalse();
        assertThat(classifier.isServicePath("/acme/issues")).isFalse();
    }

    @Test
    void followsTheConfiguredList() {
        ServicePathClassifier custom = new ServicePathClassifier(new TrafficProperties(List.of("/wiki")));
        assertThat(custom.isServicePath("/acme/demo/wiki/Home")).isTrue();
        assertThat(custom.isServicePath("/acme/demo/pulse")).isFalse();
    }
}
