package com.kholodilin.repogrowth.administration.controller;

import jakarta.validation.constraints.NotNull;

/** HTTP validation DTO (ТЗ §4) — stays on the controller layer; no domain annotations. */
public record TrackingRequest(@NotNull Boolean enabled) {}
