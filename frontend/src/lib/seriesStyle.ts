/** Canonical identity colors on the supported light theme. Status arrows are independent. */
export const METRIC_STYLES = {
  Views: "#4267b2", Visitors: "#2e8540", Clones: "#b7791f", "Unique Cloners": "#7c3aed",
  Stars: "#4267b2", Forks: "#2e8540", Watchers: "#b7791f", Contributors: "#c43c3c",
} as const;
export type SeriesStyle = { color: string; line: "solid" | "dashed" | "dotted"; symbol: string };
export const OTHER_STYLE: SeriesStyle = { color: "#64748b", line: "dashed", symbol: "diamond" };
export const MISSING_COLOR = "#9ca3af";
export const DYNAMIC_PALETTE = ["#2563eb", "#15803d", "#b45309", "#be123c", "#7e22ce", "#0e7490", "#a21caf", "#4d7c0f"];
const STORAGE = "rgm-series-styles-v1";
const slots = new Map<string, number>();
let loaded = false;
function load() {
  if (loaded) return;
  loaded = true;
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(STORAGE) ?? "{}");
    if (stored && typeof stored === "object" && !Array.isArray(stored)) {
      const used = new Set<number>();
      for (const [key, value] of Object.entries(stored)) {
        if (typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && !used.has(value)) { slots.set(key, value); used.add(value); }
      }
    }
  } catch { /* Private browsing/storage failure must not prevent chart rendering. */ }
}
export function metricColor(name: string): string | undefined {
  const canonical = name === "Unique Contributors" ? "Contributors" : name === "Unique Visitors" ? "Visitors" : name;
  return METRIC_STYLES[canonical as keyof typeof METRIC_STYLES];
}
/** Allocate sorted new domain identities once. Persistence keeps existing marks through refreshes. */
export function prepareSeriesStyles(keys: readonly string[]) {
  load();
  let next = Math.max(-1, ...slots.values()) + 1;
  for (const key of [...new Set(keys)].sort()) if (!slots.has(key) && !metricColor(key) && key !== "Other") slots.set(key, next++);
  try { localStorage.setItem(STORAGE, JSON.stringify(Object.fromEntries(slots))); } catch { /* Use the in-memory registry if storage is denied. */ }
}
export function seriesStyle(key: string): SeriesStyle {
  const metric = metricColor(key);
  if (metric) return { color: metric, line: "solid", symbol: "circle" };
  if (key === "Other") return OTHER_STYLE;
  if (!slots.has(key)) prepareSeriesStyles([key]);
  const slot = slots.get(key)!;
  // Beyond the useful palette retain a unique hue plus a stable pattern/marker signature.
  const color = slot < DYNAMIC_PALETTE.length ? DYNAMIC_PALETTE[slot] : `hsl(${((slot * 137.507764) % 360).toFixed(6)}, 65%, 35%)`;
  return { color, line: (["solid", "dashed", "dotted"] as const)[Math.floor(slot / 8) % 3], symbol: ["circle", "rect", "triangle", "diamond", "roundRect", "pin", "arrow"][Math.floor(slot / 24) % 7] };
}
export function lineStyle(key: string) {
  const style = seriesStyle(key);
  return { color: style.color, symbol: style.symbol, itemStyle: { color: style.color }, lineStyle: { color: style.color, type: style.line } };
}

const SYMBOL_GLYPH: Record<string, string> = {
  circle: "●",
  rect: "■",
  roundRect: "▣",
  triangle: "▲",
  diamond: "◆",
  pin: "▾",
  arrow: "➤",
};

/** Tooltip/legend marker glyph matching the series symbol identity (not a hardcoded circle). */
export function seriesMarkerGlyph(key: string): string {
  return SYMBOL_GLYPH[seriesStyle(key).symbol] ?? "●";
}

export function seriesMarkerHtml(key: string): string {
  const style = seriesStyle(key);
  return `<span style="color:${style.color}">${SYMBOL_GLYPH[style.symbol] ?? "●"}</span>`;
}
