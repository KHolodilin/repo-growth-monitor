import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SearchVisibilityKpis } from "./SearchVisibilityKpis";
import { emptySearchKpi } from "../test/kpiFixtures";

const { api, allowMutations } = vi.hoisted(() => ({
  api: vi.fn(),
  allowMutations: { current: true },
}));
vi.mock("../lib/api", async (original) => ({
  ...(await original<typeof import("../lib/api")>()),
  api,
}));
vi.mock("../lib/mutationAccess", () => ({
  useMutationAccess: () => ({ allowMutations: allowMutations.current }),
}));

describe("SearchVisibilityKpis", () => {
  beforeEach(() => {
    api.mockReset();
    allowMutations.current = true;
  });

  it("renders usage and band cards from the KPI endpoint", async () => {
    const payload = emptySearchKpi(13, "7d", 3);
    payload.metrics.top3 = {
      kpi: {
        value: 1,
        unit: "COUNT",
        dataStatus: "COMPLETE",
        change: { absolute: 1, percent: 100 },
        coverage: null,
        asOf: null,
      },
      series: [0, 1],
    };
    api.mockResolvedValue(payload);
    render(<SearchVisibilityKpis repositoryId={13} period="7d" />);
    expect(await screen.findByText("3 / 50")).toBeInTheDocument();
    expect(screen.getByText("47 available")).toBeInTheDocument();
    expect(screen.getByText("Top 3")).toBeInTheDocument();
  });

  it("shows read-only usage copy without mutation access", async () => {
    allowMutations.current = false;
    api.mockResolvedValue(emptySearchKpi(13, "all", 1));
    render(<SearchVisibilityKpis repositoryId={13} period="all" />);
    expect(await screen.findByText("Tracked search queries")).toBeInTheDocument();
  });

  it("surfaces KPI load errors", async () => {
    api.mockRejectedValue(new Error("kpi down"));
    render(<SearchVisibilityKpis repositoryId={13} period="7d" />);
    expect(await screen.findByText("kpi down")).toBeInTheDocument();
  });

  it("ignores late KPI responses after unmount", async () => {
    let finish: (value: unknown) => void = () => undefined;
    api.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const view = render(<SearchVisibilityKpis repositoryId={13} period="7d" />);
    view.unmount();
    finish(emptySearchKpi(13, "7d", 1));
    await Promise.resolve();
  });
});
