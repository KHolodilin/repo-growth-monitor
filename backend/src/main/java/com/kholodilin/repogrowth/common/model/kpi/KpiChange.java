package com.kholodilin.repogrowth.common.model.kpi;

import java.math.BigDecimal;

/** Absolute and percent deltas; fields are independently nullable. */
public record KpiChange(BigDecimal absolute, BigDecimal percent) {}
