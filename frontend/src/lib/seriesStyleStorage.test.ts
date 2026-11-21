import { afterEach, describe, expect, it, vi } from "vitest";
afterEach(() => { vi.restoreAllMocks(); localStorage.clear(); });
describe("persistent series identity allocation", () => {
  it("recovers malformed/colliding stored identities and retains assignments after reload", async () => {
    localStorage.setItem("rgm-series-styles-v1", JSON.stringify({ "topic:a": 0, "topic:duplicate": 0, "bad": -1, "fraction": 1.1, "text": "2" }));
    vi.resetModules(); const first = await import("./seriesStyle");
    first.prepareSeriesStyles(["topic:duplicate", "topic:a", "topic:new"]);
    expect(first.seriesStyle("topic:a").color).not.toBe(first.seriesStyle("topic:duplicate").color);
    const before = first.seriesStyle("topic:new");
    vi.resetModules(); const second = await import("./seriesStyle");
    second.prepareSeriesStyles(["topic:later", "topic:new", "topic:a"]);
    expect(second.seriesStyle("topic:new")).toEqual(before);
    expect(second.seriesStyle("Other")).toEqual(second.OTHER_STYLE);
    expect(second.seriesMarkerGlyph("Other")).toBe("◆");
    expect(second.seriesMarkerHtml("Other")).toContain("◆");
    expect(second.seriesStyle("Unknown metric").color).toBeDefined();
    expect(second.metricColor("not a fixed metric")).toBeUndefined();
  });
  it.each(["not json", "[]", "null"])("renders with invalid storage %s", async value => {
    localStorage.setItem("rgm-series-styles-v1", value); vi.resetModules(); const styles = await import("./seriesStyle");
    expect(styles.seriesStyle("source:one").color).toBeDefined();
  });
  it("keeps an in-memory stable assignment when reading/writing storage is denied", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("denied"); });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("denied"); });
    vi.resetModules(); const styles = await import("./seriesStyle");
    styles.prepareSeriesStyles(["path:/one", "path:/two"]); const before = styles.seriesStyle("path:/one");
    styles.prepareSeriesStyles(["path:/two", "path:/one", "path:/new"]);
    expect(styles.seriesStyle("path:/one")).toEqual(before);
  });
});
