import { describe, expect, it } from "vitest";
import {
  ownerPath,
  repoHref,
  repoHrefFromFullName,
  repoPath,
  repoPathFromFullName,
  repoTabQuery,
} from "./repoPaths";

describe("repoPaths", () => {
  it("builds owner and repository paths with optional suffixes", () => {
    expect(ownerPath("Alpha")).toBe("/Alpha");
    expect(repoPath("Alpha", "project")).toBe("/Alpha/project");
    expect(repoPath("Alpha", "project", "/analytics")).toBe("/Alpha/project/analytics");
    expect(repoPath("Alpha", "project", "settings")).toBe("/Alpha/project/settings");
  });

  it("parses full names and falls back when owner or repository is missing", () => {
    expect(repoPathFromFullName("Alpha/project")).toBe("/Alpha/project");
    expect(repoPathFromFullName("Alpha/nested/name", "/x")).toBe("/Alpha/nested%2Fname/x");
    expect(repoPathFromFullName("solo")).toBe("/");
    expect(repoPathFromFullName("solo", "/fallback")).toBe("/fallback");
    expect(repoPathFromFullName("/repo")).toBe("/");
  });

  it("builds tab query strings and repository hrefs", () => {
    expect(repoTabQuery(undefined)).toBe("");
    expect(repoTabQuery("traffic")).toBe("");
    expect(repoTabQuery("stats")).toBe("?tab=stats");
    expect(repoHref("Alpha", "project")).toBe("/Alpha/project");
    expect(repoHref("Alpha", "project", "search")).toBe("/Alpha/project?tab=search");
    expect(repoHrefFromFullName("Alpha/project", "topics")).toBe("/Alpha/project?tab=topics");
  });
});
