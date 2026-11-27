package com.kholodilin.repogrowth.collection.model;

/** Bounded categories persisted on collector_dispatch.last_error for failed sends. */
public enum DispatchError {
  TRANSPORT,
  HTTP_RETRY,
  HTTP_REJECTED,
  INVALID_ACCEPTANCE,
  UNTRUSTED_NODE
}
