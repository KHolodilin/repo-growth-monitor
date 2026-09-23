import { describe, expect, it } from "vitest";
import type { GrowthEvent } from "./api";
import {
  eventCategoryIcon,
  eventMarkerLabel,
  eventTypeLabel,
  eventUtcDate,
  filterGrowthEvents,
  fromDateTimeLocal,
  groupEventsByUtcDate,
  sourceBadge,
  toDateTimeLocal,
} from "./growthEvents";

const event = (partial: Partial<GrowthEvent>): GrowthEvent => ({
  id: 1,
  repositoryId: 13,
  eventAt: "2026-09-22T12:00:00Z",
  category: "COMMUNITY",
  type: "STAR_MILESTONE",
  title: "10 stars",
  source: "GITHUB",
  ...partial,
});

describe("growthEvents", () => {
  it("labels types markers and sources", () => {
    expect(eventTypeLabel("STAR_MILESTONE")).toBe("Star milestones");
    expect(eventTypeLabel("UNKNOWN_THING")).toBe("unknown thing");
    expect(eventMarkerLabel([])).toBe("");
    expect(eventMarkerLabel([event({})])).toBe("Star milestones");
    expect(eventMarkerLabel([event({}), event({ id: 2 })])).toBe("2 - GitHub events");
    expect(eventMarkerLabel([event({ source: "MANUAL" }), event({ id: 2, source: "MANUAL" })])).toBe(
      "2 - Growth events",
    );
    expect(sourceBadge("MANUAL")).toBe("MANUAL");
    expect(sourceBadge("GITHUB")).toBe("AUTO");
    expect(eventUtcDate(event({}))).toBe("2026-09-22");
  });

  it("filters groups and maps icons", () => {
    const items = [
      event({}),
      event({ id: 2, source: "MANUAL", category: "PROMOTION", type: "LINKEDIN_POST" }),
      event({ id: 3, source: "SYSTEM" }),
    ];
    expect(filterGrowthEvents(items, "all")).toHaveLength(3);
    expect(filterGrowthEvents(items, "github")).toHaveLength(2);
    expect(filterGrowthEvents(items, "promotion")).toHaveLength(1);
    expect(groupEventsByUtcDate(items)).toHaveLength(1);
    expect(eventCategoryIcon("DISCOVERABILITY")).toBe("◇");
    expect(eventCategoryIcon("COMMUNITY")).toBe("○");
    expect(eventCategoryIcon("RELEASE")).toBe("▲");
    expect(eventCategoryIcon("MILESTONE")).toBe("★");
    expect(eventCategoryIcon("PROMOTION")).toBe("✦");
    expect(eventCategoryIcon("OTHER")).toBe("•");
  });

  it("converts local datetime fields", () => {
    const local = toDateTimeLocal("2026-09-22T12:34:00Z");
    expect(local).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
    expect(fromDateTimeLocal("2026-09-22T12:34")).toMatch(/2026-09-22/);
  });
});
