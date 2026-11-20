package com.kholodilin.collector.queue;

/** Process-local result-delivery lifecycle. */
public enum DeliveryState {
  READY,
  RETRY,
  SENDING,
  DELIVERED,
  DEAD;

  public boolean isDue() {
    return this == READY || this == RETRY;
  }

  public boolean isSending() {
    return this == SENDING;
  }

  public boolean isRequeueable() {
    return this == DEAD || this == RETRY;
  }
}
