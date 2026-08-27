package com.kholodilin.repogrowth.collection.collector;

import com.kholodilin.repogrowth.collection.domain.CollectionJobType;

public interface Collector {

    CollectionJobType type();

    void collect(CollectionContext context);
}
