package com.kholodilin.collector.model.github;

import java.time.LocalDate;
import java.util.List;

/** Views or clones time series from GitHub traffic endpoints. */
public record TrafficSeries(List<Bucket> buckets) {
  public TrafficSeries {
    buckets = buckets == null ? List.of() : List.copyOf(buckets);
  }

  public record Bucket(LocalDate date, long count, long uniques) {}
}
