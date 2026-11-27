import type { RepositoryTraffic } from "../lib/api";
import { traffic as defaultTraffic } from "./fixtures";

/** Stub split traffic resource endpoints from a RepositoryTraffic fixture. */
export function respondTrafficResources(
  url: string,
  fixture: RepositoryTraffic = defaultTraffic,
): unknown | undefined {
  if (url.includes("/referrer-history") || url.includes("/path-history")) {
    return undefined;
  }
  if (url.includes("/traffic/kpi")) {
    const metric = (total: { value: number | null; growthPercent: number | null }) => ({
      value: total.value,
      unit: "COUNT",
      dataStatus: total.value == null ? "MISSING" : "COMPLETE",
      change:
        total.growthPercent == null
          ? null
          : { absolute: null, percent: total.growthPercent },
      coverage: total.value == null ? null : { observed: 7, expected: 7, unit: "repository-days" },
      asOf: null,
    });
    return {
      scope: "REPOSITORY",
      scopeId: String(fixture.repository.id),
      period: fixture.period,
      metrics: {
        views: metric(fixture.totals.views),
        visitors: metric(fixture.totals.uniqueVisitors),
        clones: metric(fixture.totals.clones),
        uniqueCloners: metric(fixture.totals.uniqueCloners),
      },
    };
  }
  if (url.includes("/traffic-history")) {
    return {
      scope: "REPOSITORY",
      scopeId: String(fixture.repository.id),
      period: fixture.period,
      points: fixture.traffic.map((point) => ({
        date: point.date,
        views: point.views,
        visitors: point.uniqueVisitors,
        clones: point.clones,
        uniqueCloners: point.uniqueCloners,
      })),
      lastCollection: fixture.lastCollection ?? null,
      trafficGap: fixture.trafficGap ?? null,
    };
  }
  if (url.includes("/referrers")) {
    return {
      repositoryId: String(fixture.repository.id),
      kind: "REFERRERS",
      snapshotAt: fixture.referrerSnapshotAt ?? null,
      rows: fixture.referrers.map((row) => ({
        key: row.referrer,
        title: null,
        views: row.views,
        uniqueVisitors: row.uniqueVisitors,
      })),
    };
  }
  if (url.includes("/paths")) {
    return {
      repositoryId: String(fixture.repository.id),
      kind: "PATHS",
      snapshotAt: fixture.pathSnapshotAt ?? null,
      rows: fixture.paths.map((row) => ({
        key: row.path,
        title: row.title ?? null,
        views: row.views,
        uniqueVisitors: row.uniqueVisitors,
        servicePath: row.servicePath,
      })),
    };
  }
  return undefined;
}
