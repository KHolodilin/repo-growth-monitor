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
            "/acme/demo/settings/secrets/actions",
            "/acme/demo/stargazers",
            "/acme/demo/network/members",
    })
    void recognizesAdministrationPages(String path) {
        assertThat(classifier.isServicePath(path)).isTrue();
    }

    /**
     * Issues, pull requests and discussions are read by outsiders, so they count as real traffic
     * even though GitHub serves them under a repository tab.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/acme/demo",
            "/acme/demo/",
            "/acme/demo/issues",
            "/acme/demo/issues/50",
            "/acme/demo/pulls",
            "/acme/demo/pull/51/files",
            "/acme/demo/discussions",
            "/acme/demo/blob/main/README.md",
            "/acme/demo/tree/main/docs",
            "/acme",
            "/",
    })
    void leavesPagesAReaderArrivesAtAlone(String path) {
        assertThat(classifier.isServicePath(path)).isFalse();
    }

    @Test
    void ignoresTheQueryStringAndLetterCase() {
        assertThat(classifier.isServicePath("/acme/demo/Pulse?period=monthly")).isTrue();
        assertThat(classifier.isServicePath("/acme/demo?tab=readme-ov-file")).isFalse();
    }

    /**
     * A repository can own a branch or a directory named like a tab, but GitHub still serves those
     * under /blob or /tree, so only the configured segment right after the repository counts.
     */
    @Test
    void onlyLooksAtTheSegmentAfterTheRepository() {
        assertThat(classifier.isServicePath("/acme/demo/tree/main/settings")).isFalse();
        assertThat(classifier.isServicePath("/acme/settings")).isFalse();
    }

    @Test
    void followsTheConfiguredList() {
        ServicePathClassifier custom = new ServicePathClassifier(new TrafficProperties(List.of("/wiki")));
        assertThat(custom.isServicePath("/acme/demo/wiki/Home")).isTrue();
        assertThat(custom.isServicePath("/acme/demo/pulse")).isFalse();
    }
}
