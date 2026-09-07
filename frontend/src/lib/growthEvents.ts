import type { GrowthEvent } from "./api";

export type EventFilter = "all" | "github" | "promotion";

export const AUTOMATIC_SETTING_LABELS: { type: string; label: string }[] = [
  { type: "README_SIGNIFICANTLY_CHANGED", label: "README significantly changed" },
  { type: "DESCRIPTION_CHANGED", label: "Description changed" },
  { type: "TOPICS_CHANGED", label: "Topics changed" },
  { type: "HOMEPAGE_CHANGED", label: "Homepage changed" },
  { type: "GOOD_FIRST_ISSUE_PUBLISHED", label: "Good first issue published" },
  { type: "EXTERNAL_ISSUE_OPENED", label: "External issue opened" },
  { type: "EXTERNAL_PR_OPENED", label: "External pull request opened" },
  { type: "EXTERNAL_PR_MERGED", label: "External pull request merged" },
  { type: "FIRST_EXTERNAL_CONTRIBUTOR", label: "First external contributor" },
  { type: "RELEASE_PUBLISHED", label: "Release published" },
  { type: "FIRST_EXTERNAL_FORK", label: "First external fork" },
  { type: "STAR_MILESTONE", label: "Star milestones" },
  { type: "FORK_MILESTONE", label: "Fork milestones" },
  { type: "CONTRIBUTOR_MILESTONE", label: "Contributor milestones" },
];

export const MANUAL_EVENT_TYPES: { type: string; label: string }[] = [
  { type: "LINKEDIN_POST", label: "LinkedIn post" },
  { type: "REDDIT_POST", label: "Reddit post" },
  { type: "HACKER_NEWS_POST", label: "Hacker News post" },
  { type: "HABR_ARTICLE", label: "Habr article" },
  { type: "DEVTO_ARTICLE", label: "DEV.to article" },
  { type: "MEDIUM_ARTICLE", label: "Medium article" },
  { type: "TELEGRAM_POST", label: "Telegram post" },
  { type: "YOUTUBE_VIDEO", label: "YouTube video" },
  { type: "CUSTOM", label: "Custom" },
];

const TYPE_LABELS = new Map<string, string>([
  ...AUTOMATIC_SETTING_LABELS.map((item) => [item.type, item.label] as const),
  ...MANUAL_EVENT_TYPES.map((item) => [item.type, item.label] as const),
]);

export function eventTypeLabel(type: string) {
  return TYPE_LABELS.get(type) ?? type.replaceAll("_", " ").toLowerCase();
}

/**
 * Chart markers sit right on the plot, so a full event title collides with the lines. A single
 * event is named by its type and a group only by how many events it holds; the titles stay in the
 * tooltip and in the event dialog.
 */
export function eventMarkerLabel(events: GrowthEvent[]) {
  if (events.length === 0) {
    return "";
  }
  if (events.length === 1) {
    return eventTypeLabel(events[0].type);
  }
  const github = events.every((event) => event.source === "GITHUB" || event.source === "SYSTEM");
  return `${events.length} - ${github ? "GitHub events" : "Growth events"}`;
}

export function eventUtcDate(event: GrowthEvent) {
  return event.eventAt.slice(0, 10);
}

export function filterGrowthEvents(events: GrowthEvent[], filter: EventFilter) {
  if (filter === "github") {
    return events.filter((event) => event.source === "GITHUB" || event.source === "SYSTEM");
  }
  if (filter === "promotion") {
    return events.filter((event) => event.category === "PROMOTION");
  }
  return events;
}

export function groupEventsByUtcDate(events: GrowthEvent[]) {
  const groups = new Map<string, GrowthEvent[]>();
  for (const event of events) {
    const date = eventUtcDate(event);
    const current = groups.get(date) ?? [];
    current.push(event);
    groups.set(date, current);
  }
  return [...groups.entries()].map(([date, items]) => ({ date, events: items }));
}

export function eventCategoryIcon(category: string) {
  switch (category) {
    case "DISCOVERABILITY":
      return "◇";
    case "COMMUNITY":
      return "○";
    case "RELEASE":
      return "▲";
    case "MILESTONE":
      return "★";
    case "PROMOTION":
      return "✦";
    default:
      return "•";
  }
}

export function sourceBadge(source: string) {
  return source === "MANUAL" ? "MANUAL" : "AUTO";
}

export function toDateTimeLocal(iso: string) {
  const date = new Date(iso);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function fromDateTimeLocal(value: string) {
  return new Date(value).toISOString();
}
