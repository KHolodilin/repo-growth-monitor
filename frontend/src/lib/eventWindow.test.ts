import { describe, expect, it } from "vitest";
import { eventWindowComparison, signedCount } from "./eventWindow";

const eventDate = "2026-10-05";

describe("eventWindowComparison", () => {
  it("sums the week before and the week starting on the event", () => {
    const comparison = eventWindowComparison({
      eventDate,
      today: "2026-10-12",
      traffic: [
        { date: "2026-09-30", uniqueVisitors: 100, clones: 18 },
        { date: "2026-10-04", uniqueVisitors: 20, clones: null },
        { date: "2026-10-05", uniqueVisitors: 340, clones: 40 },
        { date: "2026-10-12", uniqueVisitors: 999, clones: 999 },
      ],
      stats: [
        { date: "2026-09-27", stars: 10 },
        { date: "2026-10-04", stars: 11 },
        { date: "2026-10-11", stars: 17 },
      ],
      queries: [
        {
          name: "outbox",
          points: [
            { date: "2026-10-04", position: 22 },
            { date: "2026-10-08", position: 14 },
          ],
        },
        {
          name: "kafka",
          points: [
            { date: "2026-10-04", position: 10 },
            { date: "2026-10-08", position: 9 },
          ],
        },
      ],
    });
    expect(comparison.afterDays).toBe(7);
    expect(comparison.visitors).toEqual({ before: 120, after: 340, difference: 220 });
    expect(comparison.clones).toEqual({ before: 18, after: 40, difference: 22 });
    expect(comparison.stars).toEqual({ before: 1, after: 6, difference: 5 });
    expect(comparison.rank).toEqual({ kind: "move", name: "outbox", from: 22, to: 14, difference: 8 });
  });

  it("marks a short after window and an empty query list", () => {
    const partial = eventWindowComparison({
      eventDate,
      today: "2026-10-08",
      traffic: [{ date: "2026-10-06", uniqueVisitors: 3, clones: 1 }],
      stats: [{ date: "2026-10-06", stars: 4 }],
      queries: [],
    });
    expect(partial.afterDays).toBe(3);
    expect(partial.visitors.after).toBe(3);
    expect(partial.rank).toEqual({ kind: "no-queries" });

    const future = eventWindowComparison({
      eventDate: "2026-10-08",
      today: "2026-10-08",
      traffic: [],
      stats: [{ date: "2026-10-08", stars: 4 }],
      queries: [{ name: "outbox", points: [{ date: "2026-10-01", position: 4 }] }],
    });
    expect(future.afterDays).toBe(0);
    expect(future.stars).toEqual({ before: 0, after: 0, difference: 0 });
    expect(future.rank).toEqual({ kind: "none" });
  });

  it("picks the strongest rank move and skips a flat line", () => {
    const comparison = eventWindowComparison({
      eventDate,
      today: "2026-10-20",
      traffic: [{ date: "2026-10-01", uniqueVisitors: null, clones: 2 }],
      stats: [
        { date: "2026-10-02", stars: 5 },
        { date: "2026-10-04", stars: 8 },
        { date: "2026-10-06", stars: 8 },
        { date: "2026-10-09", stars: 9 },
      ],
      queries: [
        {
          name: "flat",
          points: [
            { date: "2026-10-04", position: 5 },
            { date: "2026-10-08", position: 5 },
          ],
        },
        {
          name: "worse",
          points: [
            { date: "2026-10-04", position: 4 },
            { date: "2026-10-08", position: null },
            { date: "2026-10-09", position: 12 },
          ],
        },
        {
          name: "better",
          points: [
            { date: "2026-10-01", position: 20 },
            { date: "2026-10-04", position: 12 },
            { date: "2026-10-09", position: 4 },
          ],
        },
      ],
    });
    expect(comparison.visitors.before).toBe(0);
    expect(comparison.stars.before).toBe(3);
    expect(comparison.stars.after).toBe(1);
    expect(comparison.rank).toEqual({ kind: "move", name: "better", from: 12, to: 4, difference: 8 });
  });

  it("prefers an improvement when two moves have the same size", () => {
    const comparison = eventWindowComparison({
      eventDate,
      today: "2026-10-12",
      traffic: [],
      stats: [],
      queries: [
        {
          name: "down",
          points: [
            { date: "2026-10-04", position: 4 },
            { date: "2026-10-08", position: 12 },
          ],
        },
        {
          name: "up",
          points: [
            { date: "2026-10-04", position: 22 },
            { date: "2026-10-08", position: 14 },
          ],
        },
      ],
    });
    expect(comparison.rank).toMatchObject({ name: "up", difference: 8 });
    expect(signedCount(8)).toBe("+8");
    expect(signedCount(-8)).toBe("-8");
    expect(signedCount(0)).toBe("0");
  });
});
