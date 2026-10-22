import type { TrafficNotice } from "./api";
import { collectionFailureReason } from "./collectProgress";
import { formatChartAxisDate } from "./utils";

export function formatDayMonth(value: string) {
  return new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short" }).format(new Date(`${value}T00:00:00`));
}

export function missingTrafficBadge(count: number) {
  return count === 1 ? "1 repository missing traffic data" : `${count} repositories missing traffic data`;
}

const JOB_LABELS: Record<string, string> = {
  TRAFFIC: "Traffic",
  REFERRERS: "Referrers",
  POPULAR_PATHS: "Popular Paths",
  REPOSITORY_STATS: "Repository Stats",
  GROWTH_EVENTS: "Growth Events",
};

export function portfolioNoticeText(notice: TrafficNotice) {
  if (notice.reason === "COLLECTION_FAILED") {
    return `Collection failed for ${formatChartAxisDate(notice.date)}.`;
  }
  if (notice.reason === "COLLECTION_NOT_EXECUTED") {
    return `Collection not executed for ${formatChartAxisDate(notice.date)}.`;
  }
  const end = formatDayMonth(notice.date);
  if (notice.lastTrafficDate) {
    const start = nextIsoDay(notice.lastTrafficDate);
    const day = start < notice.date ? `${formatDayMonth(start)}–${end}` : end;
    return `No GitHub traffic for ${day}. Last data ${formatChartAxisDate(notice.lastTrafficDate)}`;
  }
  return `No GitHub traffic for ${end}.`;
}

export function repositoryProblemLines(row: {
  trafficNotice?: TrafficNotice | null;
  collectionStatus?: string | null;
  jobs?: { jobType: string; status: string; errorMessage?: string | null }[];
}) {
  const lines: string[] = [];
  if (row.trafficNotice && row.trafficNotice.reason !== "MISSING_DAY") {
    lines.push(portfolioNoticeText(row.trafficNotice));
  }
  if (row.collectionStatus === "PARTIAL") {
    const failed = (row.jobs ?? []).filter((job) => job.status === "FAILED");
    const summary = failed
      .map((job) => {
        const label = JOB_LABELS[job.jobType] ?? job.jobType;
        const reason = collectionFailureReason(job.errorMessage, job.jobType);
        return reason ? `${label} ✕: ${reason}` : `${label} ✕`;
      })
      .join(", ");
    lines.push(summary ? `Partial collection: ${summary}` : "Partial collection");
  }
  return lines;
}

export function repositoryProblemBadge(count: number) {
  return count === 1 ? "1 repository" : `${count} repositories`;
}

export function partialDayTooltip(missing: number, date: string, kind: "traffic" | "stats" = "traffic") {
  const repos = missing === 1 ? "repository" : "repositories";
  const subject = kind === "stats" ? "stats" : "traffic";
  return `Partial data — ${missing} ${repos} missing ${subject} data for ${formatDayMonth(date)}`;
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
