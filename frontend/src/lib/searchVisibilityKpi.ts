import type { Period } from "../components/PeriodSelector";

export const SEARCH_QUERY_LIMIT = 50;

export type RankSample = {
  id: number;
  enabled: boolean;
  currentRank: number | null;
  points: { date: string; position: number | null }[];
};

export type BandKpi = {
  value: number | null;
  absoluteChange: number | null;
  growthPercent: number | null;
  series: (number | null)[];
};

export function searchQueryUsage(count: number, limit = SEARCH_QUERY_LIMIT) {
  const used = Math.max(0, count);
  const available = Math.max(0, limit - used);
  const progress = limit <= 0 ? 0 : Math.min(100, (used / limit) * 100);
  return { used, limit, available, progress };
}

export function bandKpi(queries: RankSample[], band: number, period: Period, today: string): BandKpi {
  const active = queries.filter((query) => query.enabled);
  const window = periodWindow(period, today, active);
  const series = window.days.map((day) => countOnDay(active, band, day));
  const value = currentCount(active, band);
  if (value == null || window.previousTo == null) {
    return { value, absoluteChange: null, growthPercent: null, series };
  }
  const previous = countAsOf(active, band, window.previousTo);
  if (previous == null) {
    return { value, absoluteChange: null, growthPercent: null, series };
  }
  const absoluteChange = value - previous;
  const growthPercent = previous === 0 ? null : (absoluteChange / previous) * 100;
  return { value, absoluteChange, growthPercent, series };
}

function periodWindow(period: Period, today: string, queries: RankSample[]) {
  if (period === "all") {
    const first = queries.map(firstDate).filter((date): date is string => date != null).sort()[0] ?? today;
    return { days: eachDate(first, today), previousTo: null as string | null };
  }
  if (period === "1d") {
    const day = shiftDays(today, -1);
    return { days: [day], previousTo: shiftDays(day, -1) };
  }
  const from = period === "7d"
    ? shiftDays(today, -6)
    : period === "90d"
      ? shiftDays(today, -89)
      : period === "1y"
        ? shiftDays(shiftYears(today, -1), 1)
        : shiftDays(today, -29);
  return { days: eachDate(from, today), previousTo: shiftDays(from, -1) };
}

function currentCount(queries: RankSample[], band: number) {
  const measured = queries.filter((query) => query.points.length > 0);
  if (measured.length === 0) {
    return null;
  }
  return measured.filter((query) => inBand(query.currentRank, band)).length;
}

function countAsOf(queries: RankSample[], band: number, asOf: string) {
  const existing = queries.filter((query) => {
    const first = firstDate(query);
    return first != null && first <= asOf;
  });
  if (existing.length === 0) {
    return null;
  }
  let count = 0;
  for (const query of existing) {
    const point = latestOnOrBefore(query, asOf);
    if (inBand(point.position, band)) {
      count += 1;
    }
  }
  return count;
}

/** A day is incomplete when a query already being tracked has no measurement. That is not a zero. */
function countOnDay(queries: RankSample[], band: number, date: string) {
  const tracked = queries.filter((query) => {
    const first = firstDate(query);
    return first != null && first <= date;
  });
  if (tracked.length === 0) {
    return null;
  }
  let count = 0;
  for (const query of tracked) {
    const point = query.points.find((item) => item.date === date);
    if (!point) {
      return null;
    }
    if (inBand(point.position, band)) {
      count += 1;
    }
  }
  return count;
}

function inBand(position: number | null, band: number) {
  return position != null && position >= 1 && position <= band;
}

function firstDate(query: RankSample) {
  if (query.points.length === 0) {
    return null;
  }
  return query.points.reduce((min, point) => (point.date < min ? point.date : min), query.points[0].date);
}

function latestOnOrBefore(query: RankSample, date: string) {
  let best: RankSample["points"][number] | null = null;
  for (const point of query.points) {
    if (point.date <= date && (best == null || point.date > best.date)) {
      best = point;
    }
  }
  return best!;
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
