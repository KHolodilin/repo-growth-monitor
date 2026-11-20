package com.kholodilin.collector.model;

import java.time.Duration;

/** Exponential backoff policy shared by GitHub technical retries and result delivery retries. */
public record RetrySettings(
    int maxAttempts, Duration initialDelay, Duration maxDelay, double multiplier, double jitter) {}
