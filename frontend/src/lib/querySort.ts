import type { SearchHistory, TopicHistory } from "./api";
import type { SortDirection } from "./tableSortPrefs";

export const QUERY_SORT_KEYS = ["name", "rank", "change", "change7d", "change30d", "best", "results", "updated"] as const;

export type QuerySortKey = (typeof QUERY_SORT_KEYS)[number];

export function sortTopicHistories(
  items: TopicHistory[],
  key: QuerySortKey | null,
  dir: SortDirection,
): TopicHistory[] {
  if (!key) {
    return items;
  }
  return [...items].sort((left, right) => {
    const compared = compareTopicRows(left, right, key, dir);
    if (compared !== 0) {
      return compared;
    }
    return left.watch.id - right.watch.id;
  });
}

export function compareTopicRows(left: TopicHistory, right: TopicHistory, key: QuerySortKey, dir: SortDirection): number {
  if (key === "name") {
    const compared = left.watch.topic.localeCompare(right.watch.topic, undefined, { sensitivity: "base" });
    return dir === "desc" ? -compared : compared;
  }
  return compareQueryRows(topicAsSearchHistory(left), topicAsSearchHistory(right), key, dir);
}

export function compareQueryRows(left: SearchHistory, right: SearchHistory, key: QuerySortKey, dir: SortDirection): number {
  if (key === "name") {
    const compared = left.query.name.localeCompare(right.query.name, undefined, { sensitivity: "base" });
    return dir === "desc" ? -compared : compared;
  }
  const leftValue = querySortValue(left, key);
  const rightValue = querySortValue(right, key);
  if (leftValue == null && rightValue == null) {
    return 0;
  }
  if (leftValue == null) {
    return 1;
  }
  if (rightValue == null) {
    return -1;
  }
  return dir === "desc" ? rightValue - leftValue : leftValue - rightValue;
}

function topicAsSearchHistory(item: TopicHistory): SearchHistory {
  return {
    query: {
      id: item.watch.id,
      repositoryId: item.watch.repositoryId,
      name: item.watch.topic,
      query: item.watch.topic,
      enabled: item.watch.enabled,
      resultLimit: item.watch.resultLimit,
    },
    currentRank: item.currentRank,
    change: item.change,
    change7d: item.change7d,
    change30d: item.change30d,
    bestRank: item.bestRank,
    points: item.points.map((point) => ({ date: point.date, position: point.position, searchRunId: point.topicRunId })),
    lastChecked: item.lastChecked,
    searchStatus: item.searchStatus,
    totalResults: item.totalResults,
    missedDates: item.missedDates,
  };
}

function queryChangeSortValue(item: SearchHistory): number | null {
  const change = item.change;
  if (!change || change.kind === "NONE") {
    return null;
  }
  if (change.kind === "UNCHANGED") {
    return 0;
  }
  if (change.kind === "IMPROVED") {
    return change.amount;
  }
  if (change.kind === "DECLINED") {
    return -change.amount;
  }
  if (change.kind === "ENTERED") {
    return change.rank == null ? 1000 : 1000 - change.rank;
  }
  return -(1000 + change.amount);
}

function querySortValue(item: SearchHistory, key: Exclude<QuerySortKey, "name">): number | null {
  if (key === "rank") {
    return item.currentRank ?? null;
  }
  if (key === "change") {
    return queryChangeSortValue(item);
  }
  if (key === "best") {
    return item.bestRank ?? null;
  }
  if (key === "results") {
    return item.totalResults ?? null;
  }
  if (key === "updated") {
    return item.lastChecked ? Date.parse(item.lastChecked) : null;
  }
  if (key === "change7d") {
    return item.change7d ?? null;
  }
  return item.change30d ?? null;
}
