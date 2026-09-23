import { describe, expect, it } from "vitest";
import type { SearchHistory, TopicHistory, TopicWatch } from "./api";
import { compareQueryRows, compareTopicRows, sortTopicHistories } from "./querySort";

describe("sortTopicHistories rank", () => {
  it("orders numeric ranks and keeps omitted ranks last when the API drops null currentRank", () => {
    const gatling = topic({ id: 1, topic: "gatling", currentRank: 36 });
    const grafana = topicFromApi({ id: 3, topic: "grafana" });
    const observability = topic({ id: 13, topic: "observability-dashboard", currentRank: 1 });
    const idempotencyKey = topic({ id: 11, topic: "idempotency-key", currentRank: 3 });

    const sorted = sortTopicHistories(
      [gatling, grafana, observability, idempotencyKey],
      "rank",
      "asc",
    );

    expect(sorted.map((item) => item.watch.topic)).toEqual([
      "observability-dashboard",
      "idempotency-key",
      "gatling",
      "grafana",
    ]);
  });

  it("puts missing ranks last in both directions", () => {
    const inTop = topic({ id: 1, topic: "in-top", currentRank: 10 });
    const omitted = topicFromApi({ id: 2, topic: "omitted" });
    const explicitNull = topic({ id: 3, topic: "explicit-null", currentRank: null });

    expect(sortTopicHistories([omitted, inTop, explicitNull], "rank", "asc").map(topicName)).toEqual([
      "in-top",
      "omitted",
      "explicit-null",
    ]);
    expect(sortTopicHistories([omitted, inTop, explicitNull], "rank", "desc").map(topicName)).toEqual([
      "in-top",
      "omitted",
      "explicit-null",
    ]);
  });

  it("sorts by name and leaves the list untouched without a key", () => {
    const beta = topic({ id: 2, topic: "beta", currentRank: 2 });
    const alpha = topic({ id: 1, topic: "alpha", currentRank: 1 });
    expect(sortTopicHistories([beta, alpha], null, "asc").map(topicName)).toEqual(["beta", "alpha"]);
    expect(sortTopicHistories([beta, alpha], "name", "asc").map(topicName)).toEqual(["alpha", "beta"]);
    expect(sortTopicHistories([beta, alpha], "name", "desc").map(topicName)).toEqual(["beta", "alpha"]);
    expect(compareTopicRows(alpha, beta, "name", "asc")).toBeLessThan(0);
  });

  it("sorts search rows by change results and timestamps", () => {
    const improved = search({ id: 1, name: "a", change: { kind: "IMPROVED", amount: 2, rank: 3 }, totalResults: 10 });
    const declined = search({ id: 2, name: "b", change: { kind: "DECLINED", amount: 1, rank: 8 }, totalResults: 20 });
    const entered = search({ id: 3, name: "c", change: { kind: "ENTERED", amount: 0, rank: 4 } });
    const enteredNone = search({ id: 4, name: "d", change: { kind: "ENTERED", amount: 0, rank: null } });
    const exited = search({ id: 5, name: "e", change: { kind: "EXITED", amount: 50, rank: null } });
    const unchanged = search({ id: 6, name: "f", change: { kind: "UNCHANGED", amount: 0, rank: 1 } });
    const none = search({ id: 7, name: "g", change: { kind: "NONE", amount: 0, rank: 1 } });
    expect(compareQueryRows(improved, declined, "change", "desc")).toBeLessThan(0);
    expect(compareQueryRows(entered, enteredNone, "change", "asc")).not.toBe(0);
    expect(compareQueryRows(exited, unchanged, "change", "asc")).not.toBe(0);
    expect(compareQueryRows(unchanged, none, "change", "asc")).not.toBe(0);
    expect(compareQueryRows(improved, declined, "results", "asc")).toBeLessThan(0);
    expect(compareQueryRows(improved, declined, "name", "desc")).toBeGreaterThan(0);
    expect(compareQueryRows(improved, { ...declined, change7d: 4 }, "change7d", "asc")).toBeLessThan(0);
    expect(compareQueryRows(improved, { ...declined, change30d: 9 }, "change30d", "asc")).toBeLessThan(0);
    expect(compareQueryRows(improved, { ...declined, bestRank: 1 }, "best", "asc")).toBeGreaterThan(0);
    expect(compareQueryRows({ ...improved, lastChecked: null }, { ...declined, lastChecked: null }, "updated", "asc")).toBe(0);
    expect(compareQueryRows(improved, declined, "updated", "desc")).not.toBe(0);
  });
});

function search(input: {
  id: number;
  name: string;
  change: SearchHistory["change"];
  totalResults?: number;
}): SearchHistory {
  return {
    query: {
      id: input.id,
      repositoryId: 13,
      name: input.name,
      query: input.name,
      enabled: true,
      resultLimit: 50,
    },
    currentRank: 4,
    change: input.change,
    change7d: 1,
    change30d: 2,
    bestRank: 2,
    points: [],
    lastChecked: `2026-09-2${input.id}T06:00:00Z`,
    totalResults: input.totalResults,
    missedDates: [],
  };
}

function topicName(item: TopicHistory) {
  return item.watch.topic;
}

function topic(input: { id: number; topic: string; currentRank: number | null }): TopicHistory {
  return {
    watch: watch(input.id, input.topic),
    currentRank: input.currentRank,
    change: { kind: "NONE", amount: 0, rank: input.currentRank },
    change7d: null,
    change30d: null,
    bestRank: input.currentRank,
    points: [],
    missedDates: [],
  };
}

/** Same shape as Jackson non_null: currentRank is absent when the repo is outside the top 50. */
function topicFromApi(input: { id: number; topic: string }): TopicHistory {
  return JSON.parse(
    JSON.stringify({
      watch: watch(input.id, input.topic),
      change: { kind: "NONE", amount: 0 },
      points: [],
      missedDates: [],
    }),
  ) as TopicHistory;
}

function watch(id: number, topic: string): TopicWatch {
  return {
    id,
    repositoryId: 13,
    topic,
    sort: "stars",
    sortOrder: "desc",
    enabled: true,
    resultLimit: 50,
  };
}
