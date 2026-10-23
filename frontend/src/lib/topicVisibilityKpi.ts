import type { Period } from "../components/PeriodSelector";
import { periodDates } from "./searchVisibilityKpi";

export type TopicRankSample = {
  slug: string;
  currentRank: number | null;
  points: { date: string; position: number | null }[];
};

export type TopicBandKpi = {
  value: number | null;
  absoluteChange: number | null;
  growthPercent: number | null;
  series: (number | null)[];
};

/**
 * Top cards count how many topics sit inside the band. The change is the last measured
 * day of the window minus the first, not whether a rank line moved toward №1.
 */
export function topicBandKpi(
  topics: TopicRankSample[],
  band: number,
  period: Period,
  today: string,
): TopicBandKpi {
  const value = currentCount(topics, band);
  const days = periodDates(period, today) ?? eachDate(earliest(topics) ?? today, today);
  const series = days.map((day) => countOnDay(topics, band, day));
  if (period === "all") {
    return { value, absoluteChange: null, growthPercent: null, series };
  }
  const measured = series.filter((point): point is number => point != null);
  if (measured.length < 2) {
    return { value, absoluteChange: null, growthPercent: null, series };
  }
  const first = measured[0];
  const last = measured[measured.length - 1];
  return {
    value,
    absoluteChange: last - first,
    growthPercent: growthPercent(last, first),
    series,
  };
}

function currentCount(topics: TopicRankSample[], band: number) {
  if (topics.length === 0) {
    return 0;
  }
  const measured = topics.filter((topic) => topic.points.length > 0);
  if (measured.length === 0) {
    return null;
  }
  return measured.filter((topic) => inBand(topic.currentRank, band)).length;
}

/** A missing row keeps the previous rank. A day with nothing to carry is null, not zero. */
function countOnDay(topics: TopicRankSample[], band: number, date: string) {
  const known = topics.filter((topic) => latestOnOrBefore(topic, date) != null);
  if (known.length === 0) {
    return null;
  }
  let count = 0;
  for (const topic of known) {
    const point = latestOnOrBefore(topic, date);
    if (point != null && inBand(point.position, band)) {
      count += 1;
    }
  }
  return count;
}

function inBand(position: number | null, band: number) {
  return position != null && position >= 1 && position <= band;
}

/** Same rounding as GrowthMath: null when the baseline is 0 and the current count is not. */
function growthPercent(current: number, previous: number) {
  if (previous === 0) {
    return current === 0 ? 0 : null;
  }
  return Math.round(((current - previous) * 1000) / previous) / 10;
}

function earliest(topics: TopicRankSample[]) {
  const dates = topics.flatMap((topic) => topic.points.map((point) => point.date)).sort();
  return dates[0] ?? null;
}

function latestOnOrBefore(topic: TopicRankSample, date: string) {
  let best: TopicRankSample["points"][number] | null = null;
  for (const point of topic.points) {
    if (point.date <= date && (best == null || point.date > best.date)) {
      best = point;
    }
  }
  return best;
}

function eachDate(from: string, to: string) {
  const dates: string[] = [];
  let cursor = from;
  while (cursor <= to) {
    dates.push(cursor);
    cursor = shiftDays(cursor, 1);
  }
  return dates;
}

function shiftDays(iso: string, days: number) {
  const date = new Date(`${iso}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}
