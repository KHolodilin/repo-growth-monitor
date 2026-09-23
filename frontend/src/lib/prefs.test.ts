import { describe, expect, it } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { usePeriod, writeStoredPeriod, writeStoredRepoPeriod } from "../components/PeriodSelector";
import { writeCookie, writeJsonCookie } from "./cookies";
import {
  dashboardTrafficChartId,
  pruneChartSelection,
  readChartSelection,
  repoSearchChartId,
  repoStatsChartId,
  repoTopicsChartId,
  repoTrafficChartId,
  resolveChartSelection,
  writeChartSelection,
} from "./chartLegend";
import { readSnapshotChartPrefs, writeSnapshotChartPrefs } from "./snapshotChartPrefs";
import { useServicePaths } from "./servicePathPrefs";
import { readTableSort, useTableSort, writeTableSort } from "./tableSortPrefs";

describe("chart and table prefs", () => {
  it("stores chart legend selections", () => {
    expect(dashboardTrafficChartId()).toBe("dashboard:traffic");
    expect(repoTrafficChartId(13)).toBe("13:traffic");
    expect(repoSearchChartId(13)).toBe("13:search");
    expect(repoTopicsChartId(13, "all")).toBe("13:topics:all");
    expect(repoStatsChartId(13)).toBe("13:stats");
    expect(readChartSelection("13:traffic")).toBeNull();
    writeChartSelection("13:traffic", {});
    expect(readChartSelection("13:traffic")).toBeNull();
    writeChartSelection("13:traffic", { Views: false, skip: true });
    expect(resolveChartSelection("13:traffic", ["Views", "Clones"])).toEqual({ Views: false, Clones: true });
    pruneChartSelection("missing", ["Views"]);
    pruneChartSelection("13:traffic", ["Views"]);
    expect(readChartSelection("13:traffic")).toEqual({ Views: false });
    document.cookie = "rgm-chart-legend=not-json; path=/";
    expect(readChartSelection("13:traffic")).toBeNull();
    writeChartSelection("bad", { Views: true });
    document.cookie = `rgm-chart-legend=${encodeURIComponent(JSON.stringify(["x"]))}; path=/`;
    expect(readChartSelection("bad")).toBeNull();
    document.cookie = `rgm-chart-legend=${encodeURIComponent(JSON.stringify({ a: 1, b: { ok: true, n: 2 } }))}; path=/`;
    expect(readChartSelection("b")).toEqual({ ok: true });
  });

  it("stores snapshot chart prefs", () => {
    expect(readSnapshotChartPrefs(13, "referrers")).toBeNull();
    writeSnapshotChartPrefs(13, "referrers", { metric: "VIEWS", keys: ["github.com", ""] });
    expect(readSnapshotChartPrefs(13, "referrers")).toEqual({ metric: "VIEWS", keys: ["github.com"] });
    writeSnapshotChartPrefs(13, "paths", { metric: "VISITORS", keys: ["readme"] });
    expect(readSnapshotChartPrefs(13, "paths")?.metric).toBe("VISITORS");
  });

  it("restores table sort and toggles direction", () => {
    const keys = ["rank", "name"] as const;
    expect(readTableSort("topic-watches", 13, keys)).toBeNull();
    writeTableSort("topic-watches", 13, { key: "rank", dir: "asc" });
    expect(readTableSort("topic-watches", 13, keys)).toEqual({ key: "rank", dir: "asc" });
    expect(readTableSort("topic-watches", 13, ["name"] as const)).toBeNull();
    writeTableSort("topic-watches", undefined, { key: "name", dir: "desc" });
    expect(readTableSort("topic-watches", "", keys)?.key).toBe("name");

    const { result, rerender } = renderHook(
      ({ scope }: { scope: number }) =>
        useTableSort({
          tableId: "search-queries",
          scope,
          keys,
          initial: { key: null, dir: "asc" },
          ascFirst: ["rank"],
        }),
      { initialProps: { scope: 13 } },
    );
    act(() => result.current.toggle("rank"));
    expect(result.current.sortKey).toBe("rank");
    expect(result.current.sortDir).toBe("asc");
    act(() => result.current.toggle("rank"));
    expect(result.current.sortDir).toBe("desc");
    act(() => result.current.toggle("name"));
    expect(result.current.sortDir).toBe("desc");
    rerender({ scope: 8 });
    expect(result.current.sortKey).toBeNull();
  });

  it("restores global and per-repository periods", () => {
    writeCookie("rgm-period", "nope");
    writeJsonCookie("rgm-period-repos", ["bad"]);
    const first = renderHook(() => usePeriod());
    expect(first.result.current[0]).toBe("30d");
    act(() => first.result.current[1]("7d"));
    expect(first.result.current[0]).toBe("7d");
    first.unmount();

    writeStoredPeriod("1y");
    writeJsonCookie("rgm-period-repos", { "13": "nope", "8": "90d" });
    writeStoredRepoPeriod("13", "1d");
    const { result, rerender } = renderHook(({ id }: { id?: string }) => usePeriod(id), {
      initialProps: { id: "13" as string | undefined },
    });
    expect(result.current[0]).toBe("1d");
    act(() => result.current[1]("all"));
    rerender({ id: "8" });
    expect(result.current[0]).toBe("90d");
    rerender({ id: undefined });
    expect(result.current[0]).toBe("1y");
  });

  it("toggles service path visibility per repository", () => {
    const { result, rerender } = renderHook(({ id }: { id?: number }) => useServicePaths(id), {
      initialProps: { id: 13 as number | undefined },
    });
    expect(result.current.showServicePaths).toBe(false);
    act(() => result.current.setShowServicePaths(true));
    expect(result.current.showServicePaths).toBe(true);
    rerender({ id: 8 });
    expect(result.current.showServicePaths).toBe(false);
  });
});
