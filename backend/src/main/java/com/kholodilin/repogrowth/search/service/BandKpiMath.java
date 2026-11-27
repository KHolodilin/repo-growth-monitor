package com.kholodilin.repogrowth.search.service;

import com.kholodilin.repogrowth.common.model.GrowthMath;
import com.kholodilin.repogrowth.common.model.period.Period;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Backend parity for frontend {@code bandKpi} / {@code topicBandKpi}. Search requires an exact day
 * sample for every tracked query; Topics carry forward the latest known rank.
 */
public final class BandKpiMath {

  public enum DayMode {
    /** Missing day for an already-tracked query makes the whole day incomplete (Search). */
    STRICT_DAY,
    /** Missing day keeps the previous rank (Topics). */
    CARRY_FORWARD
  }

  private BandKpiMath() {}

  public record RankSample(long id, boolean enabled, Integer currentRank, List<RankPoint> points) {}

  public record RankPoint(LocalDate date, Integer position) {}

  public record BandKpi(
      Integer value, Integer absoluteChange, Double growthPercent, List<Integer> series) {}

  public static BandKpi bandKpi(
      List<RankSample> samples, int band, Period period, LocalDate today, DayMode mode) {
    List<RankSample> active =
        mode == DayMode.STRICT_DAY
            ? samples.stream().filter(RankSample::enabled).toList()
            : samples;
    Window window = periodWindow(period, today, active);
    List<Integer> series = new ArrayList<>(window.days().size());
    for (LocalDate day : window.days()) {
      series.add(countOnDay(active, band, day, mode));
    }
    Integer value = currentCount(active, band, mode);
    // Incomplete days stay as null entries — List.copyOf rejects nulls.
    List<Integer> frozen = Collections.unmodifiableList(series);
    if (value == null || window.previousTo() == null) {
      return new BandKpi(value, null, null, frozen);
    }
    Integer previous = countAsOf(active, band, window.previousTo(), mode);
    if (previous == null) {
      return new BandKpi(value, null, null, frozen);
    }
    int absoluteChange = value - previous;
    return new BandKpi(value, absoluteChange, GrowthMath.percent(value, previous), frozen);
  }

  public record Usage(int used, int limit, int available, double progress) {}

  public static Usage usage(int count, int limit) {
    int used = Math.max(0, count);
    int available = Math.max(0, limit - used);
    double progress = limit <= 0 ? 0 : Math.min(100.0, (used * 100.0) / limit);
    return new Usage(used, limit, available, progress);
  }

  private record Window(List<LocalDate> days, LocalDate previousTo) {}

  private static Window periodWindow(Period period, LocalDate today, List<RankSample> samples) {
    if (period.allTime()) {
      LocalDate first =
          samples.stream()
              .map(BandKpiMath::firstDate)
              .filter(date -> date != null)
              .min(Comparator.naturalOrder())
              .orElse(today);
      return new Window(eachDate(first, today), null);
    }
    if (period == Period.D1) {
      LocalDate day = today.minusDays(1);
      return new Window(List.of(day), day.minusDays(1));
    }
    LocalDate from =
        switch (period) {
          case D7 -> today.minusDays(6);
          case D90 -> today.minusDays(89);
          case Y1 -> today.minusYears(1).plusDays(1);
          case D30 -> today.minusDays(29);
          case D1, ALL -> today.minusDays(29);
        };
    return new Window(eachDate(from, today), from.minusDays(1));
  }

  private static Integer currentCount(List<RankSample> samples, int band, DayMode mode) {
    if (mode == DayMode.CARRY_FORWARD && samples.isEmpty()) {
      return 0;
    }
    List<RankSample> measured =
        samples.stream().filter(sample -> !sample.points().isEmpty()).toList();
    if (measured.isEmpty()) {
      return null;
    }
    return (int) measured.stream().filter(sample -> inBand(sample.currentRank(), band)).count();
  }

  private static Integer countAsOf(
      List<RankSample> samples, int band, LocalDate asOf, DayMode mode) {
    List<RankSample> existing =
        samples.stream()
            .filter(sample -> {
              LocalDate first = firstDate(sample);
              return first != null && !first.isAfter(asOf);
            })
            .toList();
    if (existing.isEmpty()) {
      return null;
    }
    int count = 0;
    for (RankSample sample : existing) {
      RankPoint point = latestOnOrBefore(sample, asOf);
      if (point != null && inBand(point.position(), band)) {
        count++;
      }
    }
    return count;
  }

  private static Integer countOnDay(
      List<RankSample> samples, int band, LocalDate date, DayMode mode) {
    if (mode == DayMode.STRICT_DAY) {
      List<RankSample> tracked =
          samples.stream()
              .filter(sample -> {
                LocalDate first = firstDate(sample);
                return first != null && !first.isAfter(date);
              })
              .toList();
      if (tracked.isEmpty()) {
        return null;
      }
      int count = 0;
      for (RankSample sample : tracked) {
        RankPoint point =
            sample.points().stream()
                .filter(item -> item.date().equals(date))
                .findFirst()
                .orElse(null);
        if (point == null) {
          return null;
        }
        if (inBand(point.position(), band)) {
          count++;
        }
      }
      return count;
    }
    List<RankSample> known =
        samples.stream().filter(sample -> latestOnOrBefore(sample, date) != null).toList();
    if (known.isEmpty()) {
      return null;
    }
    int count = 0;
    for (RankSample sample : known) {
      RankPoint point = latestOnOrBefore(sample, date);
      if (point != null && inBand(point.position(), band)) {
        count++;
      }
    }
    return count;
  }

  private static boolean inBand(Integer position, int band) {
    return position != null && position >= 1 && position <= band;
  }

  private static LocalDate firstDate(RankSample sample) {
    return sample.points().stream()
        .map(RankPoint::date)
        .min(Comparator.naturalOrder())
        .orElse(null);
  }

  private static RankPoint latestOnOrBefore(RankSample sample, LocalDate date) {
    RankPoint best = null;
    for (RankPoint point : sample.points()) {
      if (!point.date().isAfter(date) && (best == null || point.date().isAfter(best.date()))) {
        best = point;
      }
    }
    return best;
  }

  private static List<LocalDate> eachDate(LocalDate from, LocalDate to) {
    List<LocalDate> dates = new ArrayList<>();
    for (LocalDate cursor = from; !cursor.isAfter(to); cursor = cursor.plusDays(1)) {
      dates.add(cursor);
    }
    return dates;
  }
}
