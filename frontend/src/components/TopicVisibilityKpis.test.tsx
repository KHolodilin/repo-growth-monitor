import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TopicVisibilityKpis } from "./TopicVisibilityKpis";
import { emptyTopicsKpi } from "../test/kpiFixtures";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (original) => ({
  ...(await original<typeof import("../lib/api")>()),
  api,
}));

describe("TopicVisibilityKpis", () => {
  beforeEach(() => {
    api.mockReset();
  });

  it("renders topic count and band cards from the KPI endpoint", async () => {
    const payload = emptyTopicsKpi(13, "7d", "language");
    payload.metrics.top10 = {
      kpi: {
        value: 2,
        unit: "COUNT",
        dataStatus: "COMPLETE",
        change: { absolute: null, percent: null },
        coverage: null,
        asOf: null,
      },
      series: [null, 2],
    };
    payload.metrics.top3 = {
      kpi: {
        value: 1,
        unit: "COUNT",
        dataStatus: "COMPLETE",
        change: { absolute: 1, percent: 50 },
        coverage: null,
        asOf: null,
      },
      series: [1],
    };
    api.mockResolvedValue(payload);
    render(
      <TopicVisibilityKpis repositoryId={13} period="7d" scope="language" topicCount={4} />,
    );
    expect(await screen.findByText("Tracked topics")).toBeInTheDocument();
    expect(screen.getByText("4")).toBeInTheDocument();
    expect(screen.getByText("Top 10")).toBeInTheDocument();
    expect(screen.getByText("Top 3")).toBeInTheDocument();
  });

  it("defaults scope to all and shows missing history when value is null", async () => {
    api.mockResolvedValue(emptyTopicsKpi(13, "7d", "all"));
    render(<TopicVisibilityKpis repositoryId={13} period="7d" topicCount={0} />);
    expect(await screen.findByText("Tracked topics")).toBeInTheDocument();
    expect(api).toHaveBeenCalledWith(
      expect.stringContaining("/topics/kpi?period=7d&scope=all"),
    );
  });

  it("surfaces KPI load errors", async () => {
    api.mockRejectedValue(new Error("topics kpi down"));
    render(<TopicVisibilityKpis repositoryId={13} period="7d" topicCount={0} />);
    expect(await screen.findByText("topics kpi down")).toBeInTheDocument();
  });

  it("ignores late KPI responses after unmount", async () => {
    let finish: (value: unknown) => void = () => undefined;
    api.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const view = render(<TopicVisibilityKpis repositoryId={13} period="7d" topicCount={1} />);
    view.unmount();
    finish(emptyTopicsKpi());
    await Promise.resolve();
  });
});
