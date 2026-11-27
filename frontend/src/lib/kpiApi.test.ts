import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  repositorySearchKpi,
  repositoryStatsKpi,
  repositoryTopicsKpi,
  repositoryTrafficKpi,
} from "./kpiApi";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("./api", async (original) => ({
  ...(await original<typeof import("./api")>()),
  api,
}));

describe("kpiApi", () => {
  beforeEach(() => {
    api.mockReset();
    api.mockResolvedValue({});
  });

  it("calls repository KPI endpoints with period and scope", async () => {
    await repositorySearchKpi(13, "7d");
    await repositoryTopicsKpi(13, "30d", "language");
    await repositoryTrafficKpi("13", "1d");
    await repositoryStatsKpi(13, "all");
    expect(api).toHaveBeenCalledWith("/api/v1/repositories/13/search/kpi?period=7d");
    expect(api).toHaveBeenCalledWith(
      "/api/v1/repositories/13/topics/kpi?period=30d&scope=language",
    );
    expect(api).toHaveBeenCalledWith("/api/v1/repositories/13/traffic/kpi?period=1d");
    expect(api).toHaveBeenCalledWith("/api/v1/repositories/13/stats/kpi?period=all");
  });
});
