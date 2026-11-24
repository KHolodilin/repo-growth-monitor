import { afterEach, describe, expect, it, vi } from "vitest";
import { publicContext, readPublicPreference, writePublicPreference } from "./publicPrefs";

afterEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
});

describe("publicPrefs", () => {
  it("ignores non-object storage payloads and oversized values", () => {
    localStorage.setItem("rgm-public-preferences-v1", "[]");
    expect(
      readPublicPreference(
        "scope",
        "period",
        (value): value is string => typeof value === "string",
      ),
    ).toBeUndefined();

    writePublicPreference("scope", "period", "7d");
    expect(
      readPublicPreference(
        "scope",
        "period",
        (value): value is string => typeof value === "string",
      ),
    ).toBe("7d");

    writePublicPreference("scope", "huge", "x".repeat(20_000));
    expect(
      readPublicPreference("scope", "huge", (value): value is string => typeof value === "string"),
    ).toBeUndefined();
  });

  it("tolerates storage failures and recognizes public contexts", () => {
    writePublicPreference("scope", "period", "30d");
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("quota");
    });
    expect(() => writePublicPreference("scope", "tab", "stats")).not.toThrow();

    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(
      readPublicPreference("scope", "tab", (value): value is string => typeof value === "string"),
    ).toBe("stats");

    expect(publicContext("public:owner:1")).toBe(true);
    expect(publicContext("admin")).toBe(false);
    expect(publicContext(undefined)).toBe(false);
  });
});
