package com.kholodilin.repogrowth.common.web;

/** Product-owned non-standard HTTP header names. */
public final class AppHttpHeaders {
  public static final String TRACE_ID = "X-Trace-Id";
  public static final String REQUEST_ID = "X-Request-ID";
  public static final String FORWARDED_FOR = "X-Forwarded-For";
  public static final String REQUESTED_WITH = "X-Requested-With";

  private AppHttpHeaders() {}
}
