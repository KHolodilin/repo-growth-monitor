package com.kholodilin.repogrowth.traffic;

import com.kholodilin.repogrowth.common.config.TrafficProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * GitHub reports popular paths without saying who opened them, so the owner reading Insights lands
 * in the same list as a visitor reading the README. Paths under the repository tabs are treated as
 * service traffic: they are still stored, but hidden unless asked for.
 */
@Component
public class ServicePathClassifier {

    private final Set<String> serviceSegments;

    public ServicePathClassifier(TrafficProperties properties) {
        this.serviceSegments = properties.servicePaths().stream()
                .map(segment -> segment.replace("/", "").toLowerCase(Locale.ROOT))
                .filter(segment -> !segment.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isServicePath(String path) {
        if (path == null) {
            return false;
        }
        String withoutQuery = path.split("[?#]", 2)[0];
        String[] segments = withoutQuery.split("/");
        // segments[0] is empty for a leading slash, then owner, repo, and the tab we care about.
        if (segments.length < 4) {
            return false;
        }
        return serviceSegments.contains(segments[3].toLowerCase(Locale.ROOT));
    }
}
