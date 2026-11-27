import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RepositoryStatsPanel } from "./RepositoryStatsPanel";
import { statsKpiFromTotals } from "../test/kpiFixtures";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (original) => ({
  ...(await original<typeof import("../lib/api")>()),
  api,
}));

const history = {
  repositoryId: 13,
  period: "7d",
  totals: {
    stars: { value: 10, absoluteChange: 2, growthPercent: 25 },
    forks: { value: 1, absoluteChange: 0, growthPercent: 0 },
    watchers: { value: 0, absoluteChange: null, growthPercent: null },
    contributors: { value: null, absoluteChange: null, growthPercent: null },
  },
  points: [{ date: "2026-11-10", stars: 10, forks: 1, watchers: 0, contributors: null }],
};

describe("RepositoryStatsPanel", () => {
  beforeEach(() => {
    api.mockReset();
  });

  it("loads KPI cards and history for the chart", async () => {
    api.mockImplementation((url: string) => {
      if (String(url).includes("/stats/kpi")) {
        return Promise.resolve(statsKpiFromTotals(13, "7d", history.totals));
      }
      return Promise.resolve(history);
    });
    render(
      <MemoryRouter>
        <RepositoryStatsPanel repositoryId={13} period="7d" />
      </MemoryRouter>,
    );
    expect(await screen.findByText("↑ +2 (+25.0%)")).toBeInTheDocument();
    expect(screen.getAllByText("Stars").length).toBeGreaterThan(0);
  });

  it("surfaces load errors", async () => {
    api.mockRejectedValue(new Error("stats offline"));
    render(
      <MemoryRouter>
        <RepositoryStatsPanel repositoryId={13} period="7d" />
      </MemoryRouter>,
    );
    expect(await screen.findByText("stats offline")).toBeInTheDocument();
  });

  it("ignores late stats responses after unmount", async () => {
    let finish: (value: unknown) => void = () => undefined;
    api.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const view = render(
      <MemoryRouter>
        <RepositoryStatsPanel repositoryId={13} period="7d" />
      </MemoryRouter>,
    );
    view.unmount();
    finish(history);
    await Promise.resolve();
  });
});
