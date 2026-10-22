import { describe, expect, it } from "vitest";
import { bandKpi, searchQueryUsage, type RankSample } from "./searchVisibilityKpi";

const today = "2026-10-22";

function query(partial: Partial<RankSample> & Pick<RankSample, "id">): RankSample {
  return {
    enabled: true,
    currentRank: null,
    points: [],
    ...partial,
  };
}

describe("search visibility KPIs", () => {
  it("counts queries against the limit", () => {
    expect(searchQueryUsage(15)).toEqual({ used: 15, limit: 50, available: 35, progress: 30 });
    expect(searchQueryUsage(50)).toEqual({ used: 50, limit: 50, available: 0, progress: 100 });
    expect(searchQueryUsage(0)).toMatchObject({ available: 50, progress: 0 });
    expect(searchQueryUsage(1, 0)).toEqual({ used: 1, limit: 0, available: 0, progress: 0 });
  });

  it("counts nested bands and leaves a gap when a tracked query was not checked", () => {
    const queries = [
      query({
        id: 1,
        currentRank: 2,
        points: [
          { date: "2026-10-20", position: 4 },
          { date: "2026-10-22", position: 2 },
        ],
      }),
      query({
        id: 2,
        currentRank: 12,
        points: [
          { date: "2026-10-20", position: 9 },
          { date: "2026-10-22", position: 12 },
        ],
      }),
    ];
    const top3 = bandKpi(queries, 3, "1d", today);
    const top10 = bandKpi(queries, 10, "1d", today);
    const top20 = bandKpi(queries, 20, "1d", today);
    expect(top3.value).toBe(1);
    expect(top10.value).toBe(1);
    expect(top20.value).toBe(2);
    expect(top20.series).toEqual([null]);
    expect(top3.absoluteChange).toBe(1);
    expect(top3.growthPercent).toBeNull();
    expect(top20.absoluteChange).toBe(0);
    expect(top20.growthPercent).toBe(0);
  });

  it("compares the previous window and skips a percent when it was zero", () => {
    const queries = [
      query({
        id: 1,
        currentRank: 1,
        points: [
          { date: "2026-10-20", position: 8 },
          { date: "2026-10-21", position: 1 },
        ],
      }),
    ];
    const rising = bandKpi(queries, 3, "1d", today);
    expect(rising.absoluteChange).toBe(1);
    expect(rising.growthPercent).toBeNull();
    expect(rising.series).toEqual([1]);

    const flat = bandKpi([
      query({
        id: 1,
        currentRank: 1,
        points: [
          { date: "2026-10-21", position: 1 },
        ],
      }),
    ], 3, "1d", today);
    expect(flat.value).toBe(1);
    expect(flat.absoluteChange).toBeNull();
  });

  it("ignores disabled queries and a day before a query existed", () => {
    const result = bandKpi([
      query({ id: 1, enabled: false, currentRank: 1, points: [{ date: "2026-10-21", position: 1 }] }),
      query({ id: 2, currentRank: 2, points: [{ date: "2026-10-22", position: 2 }] }),
    ], 3, "7d", today);
    expect(result.value).toBe(1);
    expect(result.series.filter((point) => point != null)).toEqual([1]);
  });

  it("builds the same windows as the traffic period", () => {
    const sample = [query({ id: 1, currentRank: 1, points: [{ date: today, position: 1 }] })];
    expect(bandKpi(sample, 3, "30d", today).series).toHaveLength(30);
    expect(bandKpi(sample, 3, "90d", today).series).toHaveLength(90);
    expect(bandKpi(sample, 3, "1y", today).series.length).toBeGreaterThan(360);
    const all = bandKpi(sample, 3, "all", today);
    expect(all.absoluteChange).toBeNull();
    expect(all.series.at(-1)).toBe(1);
    expect(bandKpi([query({ id: 2 })], 3, "all", today).value).toBeNull();
  });

  it("uses the newest measurement when the points are out of order", () => {
    const result = bandKpi([
      query({
        id: 1,
        currentRank: 2,
        points: [
          { date: "2026-10-01", position: 2 },
          { date: "2026-09-01", position: 15 },
        ],
      }),
    ], 10, "1d", today);
    expect(result.value).toBe(1);
    expect(result.absoluteChange).toBe(0);
  });

  it("does not treat a checked miss as a rank inside the band", () => {
    const result = bandKpi([
      query({
        id: 1,
        currentRank: null,
        points: [{ date: "2026-10-21", position: null }],
      }),
    ], 20, "1d", today);
    expect(result.value).toBe(0);
    expect(result.series).toEqual([0]);
    expect(result.absoluteChange).toBeNull();
  });
});
