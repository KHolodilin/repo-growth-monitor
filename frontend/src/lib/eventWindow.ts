import { formatNumber } from "./utils";

export type WindowMetric = {
  before: number;
  after: number;
  difference: number;
};

export type RankComparison =
  | { kind: "no-queries" }
  | { kind: "none" }
  | { kind: "move"; name: string; from: number; to: number; difference: number };

export type EventWindowComparison = {
  afterDays: number;
  visitors: WindowMetric;
  clones: WindowMetric;
  stars: WindowMetric;
  rank: RankComparison;
};

type TrafficDay = { date: string; uniqueVisitors: number | null; clones: number | null };
type StarDay = { date: string; stars: number };
type RankPoint = { date: string; position: number | null };
type RankQuery = { name: string; points: RankPoint[] };

function addUtcDays(isoDate: string, days: number) {
  const date = new Date(`${isoDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function inclusiveDays(from: string, to: string) {
  if (to < from) {
    return 0;
  }
  const start = new Date(`${from}T00:00:00Z`).getTime();
  const end = new Date(`${to}T00:00:00Z`).getTime();
  return Math.round((end - start) / 86_400_000) + 1;
}

function sumInRange(points: { date: string; value: number | null }[], from: string, to: string) {
  if (to < from) {
    return 0;
  }
  return points.reduce((total, point) => {
    if (point.date < from || point.date > to) {
      return total;
    }
    return total + (point.value ?? 0);
  }, 0);
}

function metric(points: { date: string; value: number | null }[], beforeFrom: string, beforeTo: string, afterFrom: string, afterTo: string): WindowMetric {
  const before = sumInRange(points, beforeFrom, beforeTo);
  const after = sumInRange(points, afterFrom, afterTo);
  return { before, after, difference: after - before };
}

/** Gain during the window: last snapshot in the window minus the snapshot just before it. */
function starGain(points: StarDay[], from: string, to: string) {
  if (to < from) {
    return 0;
  }
  const sorted = [...points].sort((left, right) => left.date.localeCompare(right.date));
  const inWindow = sorted.filter((point) => point.date >= from && point.date <= to);
  if (inWindow.length === 0) {
    return 0;
  }
  const baseline = [...sorted].reverse().find((point) => point.date < from);
  const end = inWindow[inWindow.length - 1];
  const start = baseline ?? inWindow[0];
  if (start.date === end.date) {
    return 0;
  }
  return end.stars - start.stars;
}

function lastPosition(points: RankPoint[], from: string, to: string) {
  return [...points]
    .filter((point) => point.position != null && point.date >= from && point.date <= to)
    .sort((left, right) => left.date.localeCompare(right.date))
    .at(-1);
}

function strongestRank(queries: RankQuery[], beforeTo: string, afterFrom: string, afterTo: string): RankComparison {
  if (queries.length === 0) {
    return { kind: "no-queries" };
  }
  let best: { name: string; from: number; to: number; difference: number } | null = null;
  for (const query of queries) {
    const baseline = lastPosition(query.points, "0000-01-01", beforeTo);
    const end = lastPosition(query.points, afterFrom, afterTo);
    if (!baseline || !end || baseline.position == null || end.position == null) {
      continue;
    }
    const difference = baseline.position - end.position;
    if (difference === 0) {
      continue;
    }
    const stronger =
      !best ||
      Math.abs(difference) > Math.abs(best.difference) ||
      (Math.abs(difference) === Math.abs(best.difference) && difference > best.difference);
    if (stronger) {
      best = { name: query.name, from: baseline.position, to: end.position, difference };
    }
  }
  return best ? { kind: "move", ...best } : { kind: "none" };
}

export function eventWindowComparison(input: {
  eventDate: string;
  today: string;
  traffic: TrafficDay[];
  stats: StarDay[];
  queries: RankQuery[];
}): EventWindowComparison {
  const beforeFrom = addUtcDays(input.eventDate, -7);
  const beforeTo = addUtcDays(input.eventDate, -1);
  const yesterday = addUtcDays(input.today, -1);
  const afterFullEnd = addUtcDays(input.eventDate, 6);
  const afterTo = yesterday < afterFullEnd ? yesterday : afterFullEnd;
  return {
    afterDays: inclusiveDays(input.eventDate, afterTo),
    visitors: metric(
      input.traffic.map((point) => ({ date: point.date, value: point.uniqueVisitors })),
      beforeFrom,
      beforeTo,
      input.eventDate,
      afterTo,
    ),
    clones: metric(
      input.traffic.map((point) => ({ date: point.date, value: point.clones })),
      beforeFrom,
      beforeTo,
      input.eventDate,
      afterTo,
    ),
    stars: (() => {
      const before = starGain(input.stats, beforeFrom, beforeTo);
      const after = starGain(input.stats, input.eventDate, afterTo);
      return { before, after, difference: after - before };
    })(),
    rank: strongestRank(input.queries, beforeTo, input.eventDate, afterTo),
  };
}

export function signedCount(value: number) {
  const text = formatNumber(Math.abs(value));
  if (value > 0) {
    return `+${text}`;
  }
  if (value < 0) {
    return `-${text}`;
  }
  return text;
}
