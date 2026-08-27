package com.kholodilin.repogrowth.collection.collector;

import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class CollectorRegistry {

    private final Map<CollectionJobType, Collector> collectors = new EnumMap<>(CollectionJobType.class);

    public CollectorRegistry(List<Collector> collectorList) {
        for (Collector collector : collectorList) {
            collectors.put(collector.type(), collector);
        }
    }

    public Collector get(CollectionJobType type) {
        Collector collector = collectors.get(type);
        if (collector == null) {
            throw new IllegalStateException("No collector for " + type);
        }
        return collector;
    }
}
