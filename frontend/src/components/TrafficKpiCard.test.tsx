import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { TrafficKpiCard } from "./TrafficKpiCard";

describe("TrafficKpiCard", () => {
  it("shows the percent against the previous period and a sparkline", () => {
    const { container } = render(
      <TrafficKpiCard label="Views" value={40} growthPercent={12.5} period="30d" series={[10, null, 40]} />,
    );
    expect(screen.getByText("Views")).toBeInTheDocument();
    expect(screen.getByText("40")).toBeInTheDocument();
    expect(screen.getByText("↑ 12.5%")).toHaveClass("text-emerald-700");
    expect(screen.getByText("vs prev 30d")).toBeInTheDocument();
    expect(container.querySelector("polyline")?.getAttribute("stroke")).toBe("#047857");
  });

  it("colors a decline red and omits a sparkline when the series is empty", () => {
    const { container, rerender } = render(
      <TrafficKpiCard label="Clones" value={2} growthPercent={-4} period="7d" series={[]} />,
    );
    expect(screen.getByText("↓ 4%")).toHaveClass("text-red-700");
    expect(screen.getByText("vs prev 7d")).toBeInTheDocument();
    expect(container.querySelector("polyline")).toBeNull();

    rerender(<TrafficKpiCard label="Clones" value={2} growthPercent={-4} period="7d" />);
    expect(screen.getByText("↓ 4%")).toBeInTheDocument();
    expect(container.querySelector("polyline")).toBeNull();
  });

  it("shows a dash when the previous period is zero and hides the comparison for all time", () => {
    const { rerender, container } = render(
      <TrafficKpiCard label="Visitors" value={3} growthPercent={null} period="1d" series={[3]} />,
    );
    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.queryByText(/vs prev/)).not.toBeInTheDocument();
    expect(container.querySelector("polyline")?.getAttribute("stroke")).toBe("#6b7280");

    rerender(<TrafficKpiCard label="Visitors" value={3} growthPercent={0} period="90d" series={[3, 3]} />);
    expect(screen.getByText("→ 0%")).toHaveClass("text-muted-foreground");
    expect(screen.getByText("vs prev 90d")).toBeInTheDocument();
    expect(container.querySelector("polyline")?.getAttribute("stroke")).toBe("#6b7280");

    rerender(<TrafficKpiCard label="Visitors" value={9} growthPercent={0} period="all" series={[1, 9]} />);
    expect(screen.queryByText(/vs prev/)).not.toBeInTheDocument();
    expect(screen.queryByText("→ 0%")).not.toBeInTheDocument();
    expect(container.querySelector("polyline")).toBeNull();
  });
});
