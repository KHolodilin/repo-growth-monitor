import { describe, expect, it } from "vitest";
import { readCookie, readJsonCookie, writeCookie, writeJsonCookie } from "./cookies";

describe("cookies", () => {
  it("reads and writes dotted names without treating them as regex", () => {
    writeCookie("repo-growth.table-sort.topic-watches", "plain");
    expect(readCookie("repo-growth.table-sort.topic-watches")).toBe("plain");
    expect(readCookie("missing")).toBeNull();
  });

  it("round-trips JSON and ignores invalid payloads", () => {
    writeJsonCookie("prefs", { a: 1 });
    expect(readJsonCookie("prefs", (value) => (value && typeof value === "object" ? value : null))).toEqual({ a: 1 });
    writeCookie("prefs", "not-json");
    expect(readJsonCookie("prefs", () => ({ ok: true }))).toBeNull();
    expect(readJsonCookie("absent", () => ({ ok: true }))).toBeNull();
  });
});
