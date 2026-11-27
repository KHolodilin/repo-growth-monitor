import {
  api,
  type CollectionRun,
  type RepositoryTraffic,
  type TrafficMetric,
  type TrafficPoint,
} from "./api";
import type { Period } from "./period";
import type { KpiValue, TrafficKpiResponse } from "./kpiApi";

type DailyHistoryResponse = {
  scope: string;
  scopeId: string;
  period: string;
  points: {
    date: string;
    views: number | null;
    visitors: number | null;
    clones: number | null;
    uniqueCloners: number | null;
  }[];
  lastCollection?: CollectionRun | null;
  trafficGap?: RepositoryTraffic["trafficGap"];
};

type SnapshotResponse = {
  repositoryId: string;
  kind: string;
  snapshotAt: string | null;
  rows: {
    key: string;
    title: string | null;
    views: number;
    uniqueVisitors: number;
    servicePath?: boolean | null;
  }[];
};

function kpiToMetric(kpi: KpiValue): TrafficMetric {
  return {
    value: kpi.value == null ? null : Number(kpi.value),
    growthPercent: kpi.change?.percent == null ? null : Number(kpi.change.percent),
    coverage: kpi.coverage
      ? { observedDays: kpi.coverage.observed, expectedDays: kpi.coverage.expected }
      : null,
    previousCoverage: null,
  };
}

export function dailyPointsToTraffic(points: DailyHistoryResponse["points"]): TrafficPoint[] {
  return points.map((point) => ({
    date: point.date,
    views: point.views,
    uniqueVisitors: point.visitors,
    clones: point.clones,
    uniqueCloners: point.uniqueCloners,
  }));
}

export function assembleRepositoryTraffic(
  repositoryId: string | number,
  period: Period,
  kpi: TrafficKpiResponse,
  history: DailyHistoryResponse,
  referrers: SnapshotResponse,
  paths: SnapshotResponse,
): RepositoryTraffic {
  return {
    repository: { id: Number(repositoryId) } as RepositoryTraffic["repository"],
    owner: { id: 0, githubId: 0, login: "", ownerType: "USER" },
    period,
    totals: {
      views: kpiToMetric(kpi.metrics.views),
      uniqueVisitors: kpiToMetric(kpi.metrics.visitors),
      clones: kpiToMetric(kpi.metrics.clones),
      uniqueCloners: kpiToMetric(kpi.metrics.uniqueCloners),
    },
    traffic: dailyPointsToTraffic(history.points || []),
    referrers: (referrers.rows || []).map((row) => ({
      referrer: row.key,
      views: row.views,
      uniqueVisitors: row.uniqueVisitors,
    })),
    referrerSnapshotAt: referrers.snapshotAt || undefined,
    paths: (paths.rows || []).map((row) => ({
      path: row.key,
      title: row.title || undefined,
      views: row.views,
      uniqueVisitors: row.uniqueVisitors,
      servicePath: Boolean(row.servicePath),
    })),
    pathSnapshotAt: paths.snapshotAt || undefined,
    lastCollection: history.lastCollection || undefined,
    trafficGap: history.trafficGap || null,
  };
}

export function fetchRepositoryTrafficResources(
  id: string | number,
  period: Period,
  showServicePaths: boolean,
  signal?: AbortSignal,
): Promise<RepositoryTraffic> {
  const opts = signal ? { signal } : undefined;
  return Promise.all([
    api<TrafficKpiResponse>(`/api/v1/repositories/${id}/traffic/kpi?period=${period}`, opts),
    api<DailyHistoryResponse>(`/api/v1/repositories/${id}/traffic-history?period=${period}`, opts),
    api<SnapshotResponse>(`/api/v1/repositories/${id}/referrers`, opts),
    api<SnapshotResponse>(
      `/api/v1/repositories/${id}/paths?includeServicePaths=${showServicePaths}`,
      opts,
    ),
  ]).then(([kpi, history, referrers, paths]) =>
    assembleRepositoryTraffic(id, period, kpi, history, referrers, paths),
  );
}
