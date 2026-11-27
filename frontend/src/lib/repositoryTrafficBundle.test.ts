import { describe, expect, it, vi } from "vitest";
import {
  assembleRepositoryTraffic,
  dailyPointsToTraffic,
  fetchRepositoryTrafficResources,
} from "./repositoryTrafficBundle";
import type { TrafficKpiResponse } from "./kpiApi";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("./api", async (original) => ({
  ...(await original<typeof import("./api")>()),
  api,
}));

const kpi: TrafficKpiResponse = {
  scope: "REPOSITORY",
  scopeId: "13",
  period: "7d",
  metrics: {
    views: {
      value: 10,
      unit: "COUNT",
      dataStatus: "COMPLETE",
      change: { absolute: 2, percent: 25 },
      coverage: { observed: 7, expected: 7, unit: "repository-days" },
      asOf: null,
    },
    visitors: {
      value: 4,
      unit: "COUNT",
      dataStatus: "COMPLETE",
      change: { absolute: -4, percent: -50 },
      coverage: { observed: 7, expected: 7, unit: "repository-days" },
      asOf: null,
    },
    clones: {
      value: 2,
      unit: "COUNT",
      dataStatus: "COMPLETE",
      change: null,
      coverage: { observed: 7, expected: 7, unit: "repository-days" },
      asOf: null,
    },
    uniqueCloners: {
      value: 1,
      unit: "COUNT",
      dataStatus: "COMPLETE",
      change: null,
      coverage: { observed: 7, expected: 7, unit: "repository-days" },
      asOf: null,
    },
  },
};

describe("repositoryTrafficBundle", () => {
  it("maps daily points visitors field to uniqueVisitors", () => {
    expect(
      dailyPointsToTraffic([
        { date: "2026-09-22", views: 5, visitors: 2, clones: 1, uniqueCloners: 1 },
      ]),
    ).toEqual([
      { date: "2026-09-22", views: 5, uniqueVisitors: 2, clones: 1, uniqueCloners: 1 },
    ]);
  });

  it("assembles repository traffic from resource payloads", () => {
    const assembled = assembleRepositoryTraffic(
      13,
      "7d",
      kpi,
      {
        scope: "REPOSITORY",
        scopeId: "13",
        period: "7d",
        points: [{ date: "2026-09-22", views: 5, visitors: 2, clones: 1, uniqueCloners: 1 }],
        lastCollection: null,
        trafficGap: { reason: "GITHUB_DATA_MISSING", date: "2026-09-23" },
      },
      {
        repositoryId: "13",
        kind: "REFERRERS",
        snapshotAt: "2026-09-23T06:00:00Z",
        rows: [{ key: "github.com", title: null, views: 8, uniqueVisitors: 3 }],
      },
      {
        repositoryId: "13",
        kind: "PATHS",
        snapshotAt: "2026-09-23T06:00:00Z",
        rows: [
          { key: "/readme", title: "Readme", views: 6, uniqueVisitors: 3, servicePath: false },
        ],
      },
    );
    expect(assembled.totals.views.value).toBe(10);
    expect(assembled.totals.views.growthPercent).toBe(25);
    expect(assembled.totals.views.coverage).toEqual({ observedDays: 7, expectedDays: 7 });
    expect(assembled.totals.clones.growthPercent).toBeNull();
    expect(assembled.traffic[0].uniqueVisitors).toBe(2);
    expect(assembled.referrers[0].referrer).toBe("github.com");
    expect(assembled.paths[0].path).toBe("/readme");
    expect(assembled.paths[0].title).toBe("Readme");
    expect(assembled.paths[0].servicePath).toBe(false);
    expect(assembled.referrerSnapshotAt).toBe("2026-09-23T06:00:00Z");
    expect(assembled.pathSnapshotAt).toBe("2026-09-23T06:00:00Z");
    expect(assembled.trafficGap?.reason).toBe("GITHUB_DATA_MISSING");
  });

  it("maps missing KPI fields and sparse snapshots to nullish defaults", () => {
    const sparseKpi: TrafficKpiResponse = {
      scope: "REPOSITORY",
      scopeId: "13",
      period: "7d",
      metrics: {
        views: {
          value: null,
          unit: "COUNT",
          dataStatus: "MISSING",
          change: { absolute: null, percent: null },
          coverage: null,
          asOf: null,
        },
        visitors: {
          value: null,
          unit: "COUNT",
          dataStatus: "MISSING",
          change: null,
          coverage: null,
          asOf: null,
        },
        clones: {
          value: null,
          unit: "COUNT",
          dataStatus: "MISSING",
          change: null,
          coverage: null,
          asOf: null,
        },
        uniqueCloners: {
          value: null,
          unit: "COUNT",
          dataStatus: "MISSING",
          change: null,
          coverage: null,
          asOf: null,
        },
      },
    };
    const assembled = assembleRepositoryTraffic(
      "13",
      "7d",
      sparseKpi,
      { scope: "REPOSITORY", scopeId: "13", period: "7d", points: undefined as unknown as [] },
      { repositoryId: "13", kind: "REFERRERS", snapshotAt: null, rows: undefined as unknown as [] },
      {
        repositoryId: "13",
        kind: "PATHS",
        snapshotAt: null,
        rows: [{ key: "/x", title: null, views: 1, uniqueVisitors: 1, servicePath: null }],
      },
    );
    expect(assembled.totals.views.value).toBeNull();
    expect(assembled.totals.views.growthPercent).toBeNull();
    expect(assembled.totals.views.coverage).toBeNull();
    expect(assembled.traffic).toEqual([]);
    expect(assembled.referrers).toEqual([]);
    expect(assembled.referrerSnapshotAt).toBeUndefined();
    expect(assembled.pathSnapshotAt).toBeUndefined();
    expect(assembled.paths[0].title).toBeUndefined();
    expect(assembled.paths[0].servicePath).toBe(false);
    expect(assembled.lastCollection).toBeUndefined();
    expect(assembled.trafficGap).toBeNull();
  });

  it("fetches KPI, history, referrers and paths", async () => {
    api.mockImplementation((url: string) => {
      if (url.includes("/traffic/kpi")) return Promise.resolve(kpi);
      if (url.includes("/traffic-history"))
        return Promise.resolve({
          scope: "REPOSITORY",
          scopeId: "13",
          period: "7d",
          points: [],
          lastCollection: null,
          trafficGap: null,
        });
      if (url.includes("/referrers"))
        return Promise.resolve({
          repositoryId: "13",
          kind: "REFERRERS",
          snapshotAt: null,
          rows: [],
        });
      if (url.includes("/paths"))
        return Promise.resolve({
          repositoryId: "13",
          kind: "PATHS",
          snapshotAt: null,
          rows: [],
        });
      return Promise.reject(new Error(url));
    });
    const signal = new AbortController().signal;
    await fetchRepositoryTrafficResources(13, "7d", true, signal);
    expect(api).toHaveBeenCalledWith("/api/v1/repositories/13/traffic/kpi?period=7d", { signal });
    expect(api).toHaveBeenCalledWith("/api/v1/repositories/13/traffic-history?period=7d", {
      signal,
    });
    expect(api).toHaveBeenCalledWith("/api/v1/repositories/13/referrers", { signal });
    expect(api).toHaveBeenCalledWith("/api/v1/repositories/13/paths?includeServicePaths=true", {
      signal,
    });
  });

  it("fetches without an AbortSignal", async () => {
    api.mockResolvedValue({
      scope: "REPOSITORY",
      scopeId: "13",
      period: "7d",
      metrics: kpi.metrics,
      points: [],
      rows: [{ key: "/svc", title: "Service", views: 1, uniqueVisitors: 1, servicePath: true }],
      snapshotAt: "2026-09-23T06:00:00Z",
      lastCollection: null,
      trafficGap: null,
    });
    // Override per-url to keep assemble happy
    api.mockImplementation((url: string) => {
      if (url.includes("/traffic/kpi")) return Promise.resolve(kpi);
      if (url.includes("/traffic-history"))
        return Promise.resolve({
          scope: "REPOSITORY",
          scopeId: "13",
          period: "7d",
          points: [],
          lastCollection: { requestId: "r1" } as never,
          trafficGap: null,
        });
      if (url.includes("/referrers"))
        return Promise.resolve({
          repositoryId: "13",
          kind: "REFERRERS",
          snapshotAt: "2026-09-23T06:00:00Z",
          rows: [],
        });
      if (url.includes("/paths"))
        return Promise.resolve({
          repositoryId: "13",
          kind: "PATHS",
          snapshotAt: "2026-09-23T06:00:00Z",
          rows: [{ key: "/svc", title: "Service", views: 1, uniqueVisitors: 1, servicePath: true }],
        });
      return Promise.reject(new Error(url));
    });
    const assembled = await fetchRepositoryTrafficResources(13, "7d", false);
    expect(api).toHaveBeenCalledWith("/api/v1/repositories/13/traffic/kpi?period=7d", undefined);
    expect(api).toHaveBeenCalledWith("/api/v1/repositories/13/paths?includeServicePaths=false", undefined);
    expect(assembled.paths[0].servicePath).toBe(true);
    expect(assembled.paths[0].title).toBe("Service");
    expect(assembled.lastCollection).toEqual({ requestId: "r1" });
  });


  it("covers remaining nullish coalescing branches", () => {
    const assembled = assembleRepositoryTraffic(
      13,
      "7d",
      kpi,
      {
        scope: "REPOSITORY",
        scopeId: "13",
        period: "7d",
        points: null as unknown as [],
        lastCollection: undefined,
        trafficGap: undefined,
      },
      {
        repositoryId: "13",
        kind: "REFERRERS",
        snapshotAt: undefined as unknown as null,
        rows: null as unknown as [],
      },
      {
        repositoryId: "13",
        kind: "PATHS",
        snapshotAt: undefined as unknown as null,
        rows: null as unknown as [],
      },
    );
    expect(assembled.traffic).toEqual([]);
    expect(assembled.referrers).toEqual([]);
    expect(assembled.paths).toEqual([]);
    expect(assembled.referrerSnapshotAt).toBeUndefined();
    expect(assembled.pathSnapshotAt).toBeUndefined();
    expect(assembled.lastCollection).toBeUndefined();
    expect(assembled.trafficGap).toBeNull();
  });

});
