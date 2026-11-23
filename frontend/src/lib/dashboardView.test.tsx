import { describe, expect, it } from "vitest";
import { repositoryHref, type DashboardViewOptions } from "./dashboardView";

describe("repositoryHref", () => {
  it("prefers a custom mapper, then fullName, then root", () => {
    const custom: DashboardViewOptions = {
      repositoryHref: (id) => `/custom/${id}`,
    };
    expect(repositoryHref(custom, 13, "Alpha/project")).toBe("/custom/13");
    expect(repositoryHref({}, 13, "Alpha/project")).toBe("/Alpha/project");
    expect(repositoryHref({}, 13, null)).toBe("/");
    expect(repositoryHref({}, 13)).toBe("/");
  });
});
