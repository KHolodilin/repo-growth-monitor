/** Calendar yesterday (UTC date string), matching chart presentation T−1. */
export function chartYesterday(today = new Date().toISOString().slice(0, 10)) {
  const day = new Date(`${today}T00:00:00Z`);
  day.setUTCDate(day.getUTCDate() - 1);
  return day.toISOString().slice(0, 10);
}

/**
 * Portfolio T−1: if any tracked repository is missing that day's snapshot, drop the day
 * (same honesty as a single-repo chart with no T−1 point). Earlier partial days stay.
 */
export function withoutIncompletePortfolioTipDay<T extends { date: string; missingRepositories?: number }>(
  points: T[],
  today = new Date().toISOString().slice(0, 10),
): T[] {
  const tip = chartYesterday(today);
  return points.filter((point) => !(point.date === tip && (point.missingRepositories ?? 0) > 0));
}
