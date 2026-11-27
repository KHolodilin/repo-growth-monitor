package com.kholodilin.repogrowth.administration.controller;

import jakarta.validation.constraints.NotNull;

public record TrackingRequest(@NotNull Boolean enabled) {}
