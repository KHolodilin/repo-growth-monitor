package com.kholodilin.repogrowth.common.api;

import java.time.Instant;

public record ApiError(
        ErrorCode code,
        String message,
        Instant timestamp,
        String traceId
) {
}
