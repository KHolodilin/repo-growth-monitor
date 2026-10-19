import type { TrafficNotice } from "./api";
import { formatChartAxisDate } from "./utils";

export function formatDayMonth(value: string) {
  return new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short" }).format(new Date(`${value}T00:00:00`));
}

export function missingTrafficBadge(count: number) {
  return count === 1 ? "1 repository missing traffic data" : `${count} repositories missing traffic data`;
}

export function portfolioNoticeText(notice: TrafficNotice) {
  if (notice.reason === "COLLECTION_FAILED") {
    return `Collection failed for ${formatChartAxisDate(notice.date)}.`;
  }
  if (notice.reason === "COLLECTION_NOT_EXECUTED") {
    return `Collection not executed for ${formatChartAxisDate(notice.date)}.`;
  }
  const day = formatDayMonth(notice.date);
  if (notice.lastTrafficDate) {
    return `No GitHub traffic for ${day}. Last data ${formatChartAxisDate(notice.lastTrafficDate)}`;
  }
  return `No GitHub traffic for ${day}.`;
}

export function partialDayTooltip(missing: number, date: string) {
  const repos = missing === 1 ? "repository" : "repositories";
  return `Partial data — ${missing} ${repos} missing traffic data for ${formatDayMonth(date)}`;
}

export function repositoryGapText(notice: TrafficNotice) {
  const date = formatChartAxisDate(notice.date);
  if (notice.reason === "GITHUB_DATA_MISSING") {
    return `GitHub traffic data is missing for ${date} (T-1).`;
  }
  if (notice.reason === "COLLECTION_NOT_EXECUTED") {
    return `Collection not executed for ${date}.`;
  }
  return `Collection failed for ${date}.`;
}

export function trafficJobWarning(notice: TrafficNotice) {
  return `Traffic — GitHub data missing for ${formatChartAxisDate(notice.date)} (T-1)`;
}

export function previousIsoDay(iso: string) {
  const [year, month, day] = iso.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  date.setUTCDate(date.getUTCDate() - 1);
  return date.toISOString().slice(0, 10);
}

export function nextIsoDay(iso: string) {
  const [year, month, day] = iso.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}

/** The warning sits on yesterday when the point is absent. A stored row hides it. */
export function trafficWarningDate(points: { date: string }[], gap?: TrafficNotice | null) {
  if (!gap || gap.reason === "MISSING_DAY") {
    return null;
  }
  const marker = gap.reason === "GITHUB_DATA_MISSING" ? gap.date : previousIsoDay(gap.date);
  if (points.some((point) => point.date === marker)) {
    return null;
  }
  return marker;
}
