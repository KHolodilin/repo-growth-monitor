import type { ChartAxis } from "./chartPresentation";
import { readCookie, writeCookie } from "./cookies";
import type { EventFilter } from "./growthEvents";

export const CHART_EVENT_FILTER_COOKIE = "chart_event_filter";
export const CHART_AXIS_COOKIE = "chart_axis";

const listeners = new Set<() => void>();

function publish() {
  for (const listener of listeners) {
    listener();
  }
}

export function subscribeChartMode(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function readChartEventFilter(): EventFilter {
  const value = readCookie(CHART_EVENT_FILTER_COOKIE);
  if (value === "all" || value === "github" || value === "promotion" || value === "off") {
    return value;
  }
  return "all";
}

export function readChartAxis(): ChartAxis {
  const value = readCookie(CHART_AXIS_COOKIE);
  if (value === "calendar" || value === "timeline") {
    return value;
  }
  return "calendar";
}

export function writeChartEventFilter(value: EventFilter) {
  writeCookie(CHART_EVENT_FILTER_COOKIE, value);
  publish();
}

export function writeChartAxis(value: ChartAxis) {
  writeCookie(CHART_AXIS_COOKIE, value);
  publish();
}
