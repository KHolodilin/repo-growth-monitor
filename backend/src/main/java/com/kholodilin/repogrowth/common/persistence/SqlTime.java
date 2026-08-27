package com.kholodilin.repogrowth.common.persistence;

import java.sql.Timestamp;
import java.time.Instant;

public final class SqlTime {

    private SqlTime() {
    }

    public static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
