import type { Period } from "../components/PeriodSelector";

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
 * Top cards count how many topics sit inside the band. Change matches Search:
 * current count vs the previous completed period's end — N/A when that baseline
 * has no measurements yet (e.g. 90d/1y before history covers the prior window).
 */
export function topicBandKpi(
  topics: TopicRankSample[],
  band: number,
  period: Period,
  today: string,
): TopicBandKpi {
  const value = currentCount(topics, band);
  const window = periodWindow(period, today, topics);
  const series = window.days.map((day) => countOnDay(topics, band, day));
  if (value == null || window.previousTo == null) {
    return { value, absoluteChange: null, growthPercent: null, series };
  }
  const previous = countAsOf(topics, band, window.previousTo);
  if (previous == null) {
    return { value, absoluteChange: null, growthPercent: null, series };
  }
  const absoluteChange = value - previous;
  return {
    value,
    absoluteChange,
    growthPercent: growthPercent(value, previous),
    series,
  };
}

function periodWindow(period: Period, today: string, topics: TopicRankSample[]) {
  if (period === "all") {
    const first = earliest(topics) ?? today;
    return { days: eachDate(first, today), previousTo: null as string | null };
  }
  if (period === "1d") {
    const day = shiftDays(today, -1);
    return { days: [day], previousTo: shiftDays(day, -1) };
  }
  const from =
    period === "7d"
      ? shiftDays(today, -6)
      : period === "90d"
        ? shiftDays(today, -89)
        : period === "1y"
          ? shiftDays(shiftYears(today, -1), 1)
          : shiftDays(today, -29);
  return { days: eachDate(from, today), previousTo: shiftDays(from, -1) };
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

function countAsOf(topics: TopicRankSample[], band: number, asOf: string) {
  const existing = topics.filter((topic) => {
    const first = firstDate(topic);
    return first != null && first <= asOf;
  });
  if (existing.length === 0) {
    return null;
  }
  let count = 0;
  for (const topic of existing) {
    const point = latestOnOrBefore(topic, asOf);
    if (point != null && inBand(point.position, band)) {
      count += 1;
    }
  }
  return count;
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

function firstDate(topic: TopicRankSample) {
  if (topic.points.length === 0) {
    return null;
  }
  return topic.points.reduce(
    (min, point) => (point.date < min ? point.date : min),
    topic.points[0].date,
  );
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

function shiftYears(iso: string, years: number) {
  const date = new Date(`${iso}T00:00:00Z`);
  date.setUTCFullYear(date.getUTCFullYear() + years);
  return date.toISOString().slice(0, 10);
}
