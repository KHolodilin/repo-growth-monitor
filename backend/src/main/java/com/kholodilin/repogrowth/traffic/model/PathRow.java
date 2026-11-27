package com.kholodilin.repogrowth.traffic.model;

/** One popular-path row from a traffic path snapshot. */
public record PathRow(
    String path, String title, int views, int uniqueVisitors, boolean servicePath) {}
