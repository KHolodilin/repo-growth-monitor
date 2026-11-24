package com.kholodilin.collector.model;

import java.net.URI;
import java.time.Duration;

/** Plain, already validated assignment execution settings. */
public record ExecutionSettings(
    URI apiBaseUrl,
    Duration lease,
    Duration pollInterval,
    Duration requestTimeout,
    int maximumFailures,
    int maximumPages,
    int maximumPayloadBytes) {}
