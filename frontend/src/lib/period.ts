/** Shared calendar periods used by charts, KPIs, and period controls. */
export const PERIODS = ["1d", "7d", "30d", "90d", "1y", "all"] as const;
export type Period = (typeof PERIODS)[number];

export function isPeriod(value: string): value is Period {
  return (PERIODS as readonly string[]).includes(value);
}

/** Days on the chart for the selected period. `all` keeps the history the points already cover. */
export function periodDates(period: Period, today: string): string[] | null {
  if (period === "all") {
    return null;
  }
  if (period === "1d") {
    return [shiftDays(today, -1)];
  }
  const from =
    period === "7d"
      ? shiftDays(today, -6)
      : period === "90d"
        ? shiftDays(today, -89)
        : period === "1y"
          ? shiftDays(shiftYears(today, -1), 1)
          : shiftDays(today, -29);
  return eachDate(from, today);
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
