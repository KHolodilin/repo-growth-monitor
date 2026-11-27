package com.kholodilin.repogrowth.traffic.model;

import java.time.Instant;
import java.util.List;

/** Latest popular-path snapshot tip for a repository. */
public record PathSnapshot(Instant snapshotAt, List<PathRow> rows) {}
