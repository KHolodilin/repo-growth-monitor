import { describe, expect, it } from "vitest";
import { topicBandKpi, type TopicRankSample } from "./topicVisibilityKpi";

const today = "2026-10-22";

function topic(slug: string, currentRank: number | null, points: { date: string; position: number | null }[]): TopicRankSample {
  return { slug, currentRank, points };
}

describe("topic visibility bands", () => {
  const topics = [
    topic("alpha", 2, [
      { date: "2026-10-20", position: 4 },
      { date: "2026-10-22", position: 2 },
    ]),
    topic("beta", 12, [
      { date: "2026-10-20", position: 12 },
      { date: "2026-10-22", position: 12 },
    ]),
    topic("gamma", null, [
      { date: "2026-10-22", position: null },
    ]),
  ];

  it("counts a rank in every band it fits and carries a missing day forward", () => {
    const top3 = topicBandKpi(topics, 3, "7d", today);
    const top10 = topicBandKpi(topics, 10, "7d", today);
    const top20 = topicBandKpi(topics, 20, "7d", today);
    expect(top3.value).toBe(1);
    expect(top10.value).toBe(1);
    expect(top20.value).toBe(2);
    expect(top20.series[0]).toBeNull();
    expect(top20.series.at(-2)).toBe(2);
    expect(top3.series.filter((point) => point != null).at(-1)).toBe(1);
  });

  it("uses the last measured day minus the first and omits a percent from zero", () => {
    const rising = topicBandKpi([
      topic("alpha", 1, [
        { date: "2026-10-21", position: null },
        { date: "2026-10-22", position: 1 },
      ]),
    ], 3, "7d", today);
    expect(rising.absoluteChange).toBe(1);
    expect(rising.growthPercent).toBeNull();
  });

  it("shows no change until two days in the window are measured", () => {
    const single = topicBandKpi([
      topic("alpha", 1, [{ date: "2026-10-22", position: 1 }]),
    ], 3, "1d", today);
    expect(single.value).toBe(1);
    expect(single.absoluteChange).toBeNull();
    expect(single.series).toEqual([null]);
  });

  it("hides the comparison for the full history", () => {
    const all = topicBandKpi(topics, 20, "all", today);
    expect(all.absoluteChange).toBeNull();
    expect(all.growthPercent).toBeNull();
  });

  it("returns zero for an empty scope and N/A when nothing has been measured", () => {
    expect(topicBandKpi([], 3, "7d", today).value).toBe(0);
    expect(topicBandKpi([topic("alpha", null, [])], 3, "7d", today).value).toBeNull();
  });

  it("rounds a percent from the first measured count", () => {
    const kpi = topicBandKpi([
      topic("alpha", 1, [
        { date: "2026-10-16", position: 1 },
        { date: "2026-10-22", position: 1 },
      ]),
      topic("beta", 2, [{ date: "2026-10-22", position: 2 }]),
    ], 3, "7d", today);
    expect(kpi.absoluteChange).toBe(1);
    expect(kpi.growthPercent).toBe(100);
  });

  it("keeps a flat count as zero change with a zero percent", () => {
    const flat = topicBandKpi([
      topic("alpha", 2, [
        { date: "2026-10-16", position: 2 },
        { date: "2026-10-22", position: 2 },
      ]),
    ], 3, "7d", today);
    expect(flat.absoluteChange).toBe(0);
    expect(flat.growthPercent).toBe(0);
  });
});
