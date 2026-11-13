import { beforeEach, describe, expect, it } from "vitest";
import {
  DEFAULT_LANDING_DIRECTORY,
  parseOwnerOffset,
  parseOwnerPageSize,
  parseOwnerSort,
  readLandingDirectoryPrefs,
  writeLandingDirectoryPrefs,
} from "./landingPrefs";
import { writePublicPreference } from "./publicPrefs";

beforeEach(() => localStorage.clear());

describe("landingPrefs", () => {
  it("keeps directory prefs in the landing namespace and rejects invalid values", () => {
    expect(readLandingDirectoryPrefs()).toEqual(DEFAULT_LANDING_DIRECTORY);
    writeLandingDirectoryPrefs({ sort: "forks", order: "asc", limit: 50 });
    expect(readLandingDirectoryPrefs()).toEqual({ sort: "forks", order: "asc", limit: 50 });
    writePublicPreference("landing", "directory", { sort: "nope", order: "desc", limit: 25 });
    expect(readLandingDirectoryPrefs()).toEqual(DEFAULT_LANDING_DIRECTORY);
    expect(parseOwnerSort("watchers")).toBe("watchers");
    expect(parseOwnerSort("raw")).toBeUndefined();
    expect(parseOwnerPageSize("25")).toBe(25);
    expect(parseOwnerPageSize("")).toBeUndefined();
    expect(parseOwnerPageSize("12")).toBeUndefined();
    expect(parseOwnerOffset("3")).toBe(3);
    expect(parseOwnerOffset("")).toBe(0);
    expect(parseOwnerOffset("-1")).toBe(0);
    expect(parseOwnerOffset("10001")).toBe(0);
  });
});
