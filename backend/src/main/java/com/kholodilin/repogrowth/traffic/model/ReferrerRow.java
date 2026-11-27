package com.kholodilin.repogrowth.traffic.model;

/** One referrer row from a traffic referrer snapshot. */
public record ReferrerRow(String referrer, int views, int uniqueVisitors) {}
