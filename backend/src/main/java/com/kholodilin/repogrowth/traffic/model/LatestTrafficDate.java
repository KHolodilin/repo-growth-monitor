package com.kholodilin.repogrowth.traffic.model;

import java.time.LocalDate;

/** Latest stored traffic date per tracked repository (date may be null). */
public record LatestTrafficDate(long repositoryId, LocalDate date) {}
