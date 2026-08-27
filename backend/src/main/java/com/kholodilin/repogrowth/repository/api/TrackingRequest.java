package com.kholodilin.repogrowth.repository.api;

import jakarta.validation.constraints.NotNull;

public record TrackingRequest(@NotNull Boolean enabled) {
}
