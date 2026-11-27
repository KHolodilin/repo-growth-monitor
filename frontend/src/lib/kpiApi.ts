import { api } from "./api";
import type { Period } from "./period";

export type KpiChange = {
  absolute: number | null;
  percent: number | null;
};

export type KpiValue = {
  value: number | null;
  unit: string;
  dataStatus: string;
  change: KpiChange | null;
  coverage: {
    observed: number;
    expected: number;
    unit?: string;
  } | null;
  asOf: string | null;
};

export type BandMetric = {
  kpi: KpiValue;
  series: (number | null)[];
};

export type SearchKpiResponse = {
  scope: string;
  scopeId: string;
  period: string;
  metrics: {
    top3: BandMetric;
    top10: BandMetric;
    top20: BandMetric;
  };
  usage: {
    used: number;
    limit: number;
    available: number;
    progress: number;
  };
};

export type TopicsKpiResponse = {
  scope: string;
  scopeId: string;
  period: string;
  topicScope: string;
  metrics: {
    top3: BandMetric;
    top10: BandMetric;
    top20: BandMetric;
  };
};

export type TrafficKpiResponse = {
  scope: string;
  scopeId: string;
  period: string;
  metrics: {
    views: KpiValue;
    visitors: KpiValue;
    clones: KpiValue;
    uniqueCloners: KpiValue;
  };
};

export type StatsKpiResponse = {
  scope: string;
  scopeId: string;
  period: string;
  metrics: {
    stars: KpiValue;
    forks: KpiValue;
    watchers: KpiValue;
    contributors: KpiValue;
  };
};

export function repositorySearchKpi(repositoryId: string | number, period: Period) {
  return api<SearchKpiResponse>(
    `/api/v1/repositories/${repositoryId}/search/kpi?period=${period}`,
  );
}

export function repositoryTopicsKpi(
  repositoryId: string | number,
  period: Period,
  scope: "all" | "language" = "all",
) {
  return api<TopicsKpiResponse>(
    `/api/v1/repositories/${repositoryId}/topics/kpi?period=${period}&scope=${scope}`,
  );
}

export function repositoryTrafficKpi(repositoryId: string | number, period: Period) {
  return api<TrafficKpiResponse>(
    `/api/v1/repositories/${repositoryId}/traffic/kpi?period=${period}`,
  );
}

export function repositoryStatsKpi(repositoryId: string | number, period: Period) {
  return api<StatsKpiResponse>(`/api/v1/repositories/${repositoryId}/stats/kpi?period=${period}`);
}
