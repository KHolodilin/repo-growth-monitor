/** Minimal typed KPI payloads for Vitest API mocks. */

const missing = {
  value: null as number | null,
  unit: "COUNT",
  dataStatus: "MISSING",
  change: null as { absolute: number | null; percent: number | null } | null,
  coverage: null,
  asOf: null as string | null,
};

const missingBand = { kpi: missing, series: [] as (number | null)[] };

export function emptySearchKpi(
  repositoryId: string | number = 13,
  period = "7d",
  used = 0,
) {
  const limit = 50;
  return {
    scope: "REPOSITORY",
    scopeId: String(repositoryId),
    period,
    metrics: { top3: missingBand, top10: missingBand, top20: missingBand },
    usage: {
      used,
      limit,
      available: Math.max(0, limit - used),
      progress: limit <= 0 ? 0 : Math.min(100, (used / limit) * 100),
    },
  };
}

export function emptyTopicsKpi(
  repositoryId: string | number = 13,
  period = "7d",
  topicScope = "all",
) {
  return {
    scope: "REPOSITORY",
    scopeId: String(repositoryId),
    period,
    topicScope,
    metrics: { top3: missingBand, top10: missingBand, top20: missingBand },
  };
}

export function statsKpiFromTotals(
  repositoryId: string | number,
  period: string,
  totals: Record<
    "stars" | "forks" | "watchers" | "contributors",
    { value: number | null; absoluteChange: number | null; growthPercent: number | null }
  >,
) {
  const metric = (total: {
    value: number | null;
    absoluteChange: number | null;
    growthPercent: number | null;
  }) => ({
    value: total.value,
    unit: "COUNT",
    dataStatus: total.value == null ? "MISSING" : "COMPLETE",
    change:
      total.absoluteChange == null && total.growthPercent == null
        ? null
        : { absolute: total.absoluteChange, percent: total.growthPercent },
    coverage: null,
    asOf: null,
  });
  return {
    scope: "REPOSITORY",
    scopeId: String(repositoryId),
    period,
    metrics: {
      stars: metric(totals.stars),
      forks: metric(totals.forks),
      watchers: metric(totals.watchers),
      contributors: metric(totals.contributors),
    },
  };
}

/** Route common KPI GETs for repository detail mocks. */
export function respondKpi(url: string): unknown | undefined {
  if (url.includes("/search/kpi")) {
    return emptySearchKpi();
  }
  if (url.includes("/topics/kpi")) {
    return emptyTopicsKpi();
  }
  if (url.includes("/stats/kpi")) {
    return statsKpiFromTotals(13, "7d", {
      stars: { value: null, absoluteChange: null, growthPercent: null },
      forks: { value: null, absoluteChange: null, growthPercent: null },
      watchers: { value: null, absoluteChange: null, growthPercent: null },
      contributors: { value: null, absoluteChange: null, growthPercent: null },
    });
  }
  return undefined;
}
