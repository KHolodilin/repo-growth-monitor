const PREFIX = "repo-growth.referrer-chart.";
const MAX_AGE = 60 * 60 * 24 * 365;

export type ReferrerMetric = "VISITORS" | "VIEWS";

export type ReferrerChartPrefs = {
  metric: ReferrerMetric;
  sources: string[];
};

function cookieAttributes() {
  return `path=/; max-age=${MAX_AGE}; SameSite=Lax`;
}

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

function writeCookie(name: string, value: string) {
  document.cookie = `${name}=${encodeURIComponent(value)}; ${cookieAttributes()}`;
}

function cookieName(repositoryId: number | string) {
  return `${PREFIX}${repositoryId}`;
}

export function readReferrerChartPrefs(repositoryId: number | string): ReferrerChartPrefs | null {
  const raw = readCookie(cookieName(repositoryId));
  if (!raw) {
    return null;
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return null;
    }
    const record = parsed as { metric?: unknown; sources?: unknown };
    const metric = record.metric === "VIEWS" ? "VIEWS" : record.metric === "VISITORS" ? "VISITORS" : null;
    if (!metric || !Array.isArray(record.sources)) {
      return null;
    }
    const sources = record.sources.filter((item): item is string => typeof item === "string" && item.length > 0);
    return { metric, sources };
  } catch {
    return null;
  }
}

export function writeReferrerChartPrefs(repositoryId: number | string, prefs: ReferrerChartPrefs) {
  writeCookie(cookieName(repositoryId), JSON.stringify(prefs));
}

export function defaultTopSources(
  sources: { source: string; views: number; uniqueVisitors: number }[],
  metric: ReferrerMetric,
  limit = 4,
) {
  return [...sources]
    .sort((left, right) => {
      const leftValue = metric === "VISITORS" ? left.uniqueVisitors : left.views;
      const rightValue = metric === "VISITORS" ? right.uniqueVisitors : right.views;
      if (rightValue !== leftValue) {
        return rightValue - leftValue;
      }
      const leftSecondary = metric === "VISITORS" ? left.views : left.uniqueVisitors;
      const rightSecondary = metric === "VISITORS" ? right.views : right.uniqueVisitors;
      return rightSecondary - leftSecondary;
    })
    .slice(0, limit)
    .map((item) => item.source);
}
