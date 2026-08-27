package com.kholodilin.repogrowth.collection.collector;

import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.repository.domain.Repository;

public record CollectionContext(
        CollectionJob job,
        Repository repository,
        String ownerLogin
) {
}
