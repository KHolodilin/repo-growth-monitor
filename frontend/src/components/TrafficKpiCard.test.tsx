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

  it("shows an absolute level change and keeps the series color", () => {
    const { container, rerender } = render(
      <TrafficKpiCard
        label="Stars"
        value={27}
        growthPercent={28.6}
        absoluteChange={6}
        period="30d"
        series={[21, 27]}
        sparklineColor="#5470c6"
      />,
    );
    expect(screen.getByText("27")).toBeInTheDocument();
    expect(screen.getByText("↑ +6 (+28.6%)")).toHaveClass("text-emerald-700");
    expect(screen.getByText("vs prev 30d")).toBeInTheDocument();
    expect(container.querySelector("polyline")?.getAttribute("stroke")).toBe("#5470c6");

    rerender(
      <TrafficKpiCard
        label="Forks"
        value={19}
        growthPercent={-13.6}
        absoluteChange={-3}
        period="30d"
        series={[22, 19]}
        sparklineColor="#91cc75"
      />,
    );
    expect(screen.getByText("↓ -3 (-13.6%)")).toHaveClass("text-red-700");
    expect(container.querySelector("polyline")?.getAttribute("stroke")).toBe("#91cc75");

    rerender(
      <TrafficKpiCard
        label="Watchers"
        value={0}
        growthPercent={0}
        absoluteChange={0}
        period="30d"
        series={[0, 0]}
        sparklineColor="#fac858"
      />,
    );
    expect(screen.getByText("0")).toBeInTheDocument();
    expect(screen.getByText("— No change")).toHaveClass("text-muted-foreground");
    expect(screen.getByText("vs prev 30d")).toBeInTheDocument();
    const points = container.querySelector("polyline")?.getAttribute("points")?.split(" ") ?? [];
    expect(container.querySelector("polyline")?.getAttribute("stroke")).toBe("#fac858");
    expect(new Set(points.map((point) => point.split(",")[1])).size).toBe(1);

    rerender(
      <TrafficKpiCard
        label="Stars"
        value={6}
        growthPercent={null}
        absoluteChange={6}
        period="7d"
        series={[0, 6]}
        sparklineColor="#5470c6"
      />,
    );
    expect(screen.getByText("↑ +6")).toBeInTheDocument();
    expect(screen.queryByText(/\(%/)).not.toBeInTheDocument();
    expect(screen.getByText("vs prev 7d")).toBeInTheDocument();

    rerender(
      <TrafficKpiCard
        label="Stars"
        value={35}
        growthPercent={null}
        absoluteChange={null}
        period="1d"
        series={[35]}
        sparklineColor="#5470c6"
      />,
    );
    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.queryByText(/vs prev/)).not.toBeInTheDocument();
    expect(container.querySelector("polyline")).not.toBeNull();

    rerender(
      <TrafficKpiCard
        label="Stars"
        value={null}
        growthPercent={null}
        absoluteChange={null}
        period="30d"
        series={[]}
      />,
    );
    expect(screen.getAllByText("—")).toHaveLength(2);
    expect(container.querySelector("polyline")).toBeNull();

    rerender(
      <TrafficKpiCard
        label="Stars"
        value={27}
        growthPercent={28.6}
        absoluteChange={6}
        period="all"
        series={[21, 27]}
        sparklineColor="#5470c6"
        note="Sum of repository counts"
      />,
    );
    expect(screen.getByText("27")).toBeInTheDocument();
    expect(screen.getByText("Sum of repository counts")).toBeInTheDocument();
    expect(screen.queryByText("↑ +6 (+28.6%)")).not.toBeInTheDocument();
    expect(screen.queryByText(/vs prev/)).not.toBeInTheDocument();
    expect(container.querySelector("polyline")).toBeNull();

    rerender(
      <TrafficKpiCard
        label="Top 10"
        value={null}
        growthPercent={null}
        absoluteChange={null}
        period="30d"
        preserveGaps
        missingHistory
      />,
    );
    expect(screen.getAllByText("N/A").length).toBeGreaterThan(0);
    expect(container.querySelector("polyline")).toBeNull();
  });
});
