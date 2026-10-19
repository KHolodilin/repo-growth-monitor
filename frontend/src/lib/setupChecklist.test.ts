import { describe, expect, it } from "vitest";
import { parseGitHubAccess, parsePlannerWindow } from "./githubAccess";
import { TRAFFIC_PERMISSION } from "./githubAccess";
import { checklistSchedule, collectionJobLines, headerSchedule } from "./setupChecklist";

const utc = { timezone: "UTC", from: "10:00", to: "18:00", open: false };

describe("setup checklist copy", () => {
  it("names the window and the first collection", () => {
    expect(checklistSchedule(utc)).toBe(
      "Auto-collect runs every day from 10:00 to 18:00 UTC. The first collection starts as soon as a repository is enabled.",
    );
    expect(checklistSchedule({ ...utc, timezone: "Europe/Moscow", from: "09:30", to: "17:00" })).toBe(
      "Auto-collect runs every day from 09:30 to 17:00 Europe/Moscow. The first collection starts as soon as a repository is enabled.",
    );
  });

  it("keeps the window line and says when today's run has not started", () => {
    expect(headerSchedule({ ...utc, open: true })).toBe("Auto-collect runs every day from 10:00 to 18:00 UTC.");
    expect(headerSchedule(utc)).toBe("Auto-collect runs every day from 10:00 to 18:00 UTC. It has not started yet today.");
    expect(headerSchedule({ timezone: "Europe/Berlin", from: "09:30", to: "17:00", open: false })).toContain("Europe/Berlin");
  });

  it("lists the five collection jobs and shows a failure immediately", () => {
    const lines = collectionJobLines(
      [
        { jobType: "TRAFFIC", status: "SUCCESS" },
        { jobType: "REFERRERS", status: "FAILED", errorMessage: "  rate limit  " },
        { jobType: "POPULAR_PATHS", status: "FAILED", errorMessage: "   " },
        { jobType: "REPOSITORY_STATS", status: "RUNNING" },
        { jobType: "GROWTH_EVENTS", status: "RETRY" },
      ],
      false,
    );
    expect(lines.map((line) => `${line.mark} ${line.label}`)).toEqual([
      "✓ Traffic",
      "✕ Referrers",
      "✕ Popular Paths",
      "… Repository Stats",
      "… Growth Events",
    ]);
    expect(lines[1].detail).toBe("rate limit");
    expect(lines[2].detail).toBeNull();
  });

  it("uses the administration sentence for a missing traffic permission", () => {
    const lines = collectionJobLines([{ jobType: "TRAFFIC", status: "FAILED", errorMessage: "403" }], true);
    expect(lines[0]).toMatchObject({ mark: "✕", label: "Traffic", detail: TRAFFIC_PERMISSION });
    expect(collectionJobLines(undefined, false)[0].mark).toBe("○");
    expect(collectionJobLines([{ jobType: "TRAFFIC", status: "READY" }], false)[0].mark).toBe("○");
  });

  it("reads the planner from system status", () => {
    expect(parsePlannerWindow(null)).toBeNull();
    expect(parsePlannerWindow([])).toBeNull();
    expect(parsePlannerWindow({ from: "", to: "18:00", open: false })).toBeNull();
    expect(parsePlannerWindow({ from: "10:00", to: "", open: false })).toBeNull();
    expect(parsePlannerWindow({ from: "10:00", to: "18:00", open: "yes" })).toBeNull();
    expect(parsePlannerWindow({ timezone: "", from: "10:00", to: "18:00", open: true })?.timezone).toBe("UTC");
    expect(parsePlannerWindow({ from: "10:00", to: "18:00", open: true })).toEqual({
      timezone: "UTC",
      from: "10:00",
      to: "18:00",
      open: true,
    });
    expect(parsePlannerWindow({ timezone: "Europe/Moscow", from: "10:00", to: "18:00", open: false })?.timezone).toBe(
      "Europe/Moscow",
    );
    expect(parseGitHubAccess(null)).toEqual({ tokenState: "OK", rateLimitReset: null, planner: null });
    expect(parseGitHubAccess({ tokenState: "OTHER", planner: utc })).toMatchObject({ tokenState: "OK", planner: utc });
    expect(parseGitHubAccess({ tokenState: "MISSING", rateLimitReset: null, planner: { from: "10:00", to: "18:00", open: false } })).toMatchObject({
      tokenState: "MISSING",
      planner: { timezone: "UTC", open: false },
    });
  });
});
