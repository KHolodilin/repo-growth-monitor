package com.kholodilin.repogrowth.events.detect;

import java.time.Instant;

public record CandidateEvent(
    Instant eventAt,
    String category,
    String type,
    String title,
    String description,
    String url,
    String source,
    String externalId) {}
