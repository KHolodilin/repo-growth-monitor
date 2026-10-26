import type { ChartAxis } from "./chartPresentation";
import { readJsonCookie, writeJsonCookie } from "./cookies";
import type { EventFilter } from "./growthEvents";

export const CHART_MODE_COOKIE = "chart_mode";

type ChartModeChoice = { eventFilter: EventFilter; axis: ChartAxis };

const listeners = new Map<string, Set<() => void>>();

function isEventFilter(value: unknown): value is EventFilter {
  return value === "all" || value === "github" || value === "promotion" || value === "off";
}

function isAxis(value: unknown): value is ChartAxis {
  return value === "calendar" || value === "timeline";
}

function choiceOf(value: unknown): ChartModeChoice | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  const record = value as { eventFilter?: unknown; axis?: unknown };
  if (!isEventFilter(record.eventFilter) && !isAxis(record.axis)) {
    return null;
  }
  return {
    eventFilter: isEventFilter(record.eventFilter) ? record.eventFilter : "all",
    axis: isAxis(record.axis) ? record.axis : "calendar",
  };
}

function readMap(): Record<string, ChartModeChoice> {
  return readJsonCookie(CHART_MODE_COOKIE, (value) => {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      return null;
    }
    const stored: Record<string, ChartModeChoice> = {};
    for (const [context, item] of Object.entries(value)) {
      const choice = choiceOf(item);
      if (choice) {
        stored[context] = choice;
      }
    }
    return stored;
  }) ?? {};
}

function publish(context: string) {
  const group = listeners.get(context);
  if (!group) {
    return;
  }
  for (const listener of group) {
    listener();
  }
}

function writeChoice(context: string, patch: Partial<ChartModeChoice>) {
  const stored = readMap();
  const current = stored[context] ?? { eventFilter: "all", axis: "calendar" };
  stored[context] = { ...current, ...patch };
  writeJsonCookie(CHART_MODE_COOKIE, stored);
  publish(context);
}

export function subscribeChartMode(context: string, listener: () => void) {
  const group = listeners.get(context) ?? new Set();
  group.add(listener);
  listeners.set(context, group);
  return () => {
    group.delete(listener);
  };
}

export function readChartEventFilter(context: string): EventFilter {
  return readMap()[context]?.eventFilter ?? "all";
}

export function readChartAxis(context: string): ChartAxis {
  return readMap()[context]?.axis ?? "calendar";
}

export function writeChartEventFilter(context: string, value: EventFilter) {
  writeChoice(context, { eventFilter: value });
}

export function writeChartAxis(context: string, value: ChartAxis) {
  writeChoice(context, { axis: value });
}
