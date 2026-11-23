/** Shared calendar periods used by charts, KPIs, and period controls. */
export const PERIODS = ["1d", "7d", "30d", "90d", "1y", "all"] as const;
export type Period = (typeof PERIODS)[number];

export function isPeriod(value: string): value is Period {
  return (PERIODS as readonly string[]).includes(value);
}
