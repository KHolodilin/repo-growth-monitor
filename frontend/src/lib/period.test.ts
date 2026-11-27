import { describe, expect, it } from "vitest";
import { isPeriod, periodDates } from "./period";

describe("period", () => {
  it("recognizes supported wire values", () => {
    expect(isPeriod("7d")).toBe(true);
    expect(isPeriod("2d")).toBe(false);
  });

  it("builds calendar windows for every period", () => {
    const today = "2026-11-10";
    expect(periodDates("all", today)).toBeNull();
    expect(periodDates("1d", today)).toEqual(["2026-11-09"]);
    expect(periodDates("7d", today)).toHaveLength(7);
    expect(periodDates("30d", today)).toHaveLength(30);
    expect(periodDates("90d", today)).toHaveLength(90);
    expect(periodDates("1y", today)!.length).toBeGreaterThan(360);
  });
});
