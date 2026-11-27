package com.kholodilin.repogrowth.traffic.model;

import java.time.Instant;
import java.util.List;

/** Latest referrer snapshot tip for a repository. */
public record ReferrerSnapshot(Instant snapshotAt, List<ReferrerRow> rows) {}
