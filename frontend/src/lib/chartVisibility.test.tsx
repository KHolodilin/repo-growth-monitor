import { render, screen } from "@testing-library/react";
import { renderToString } from "react-dom/server";
import { describe, expect, it } from "vitest";
import {
  publishChartVisibility,
  readChartVisibility,
  resetChartVisibilityStore,
  subscribeChartVisibility,
  useChartVisibility,
} from "./chartVisibility";

function Probe({ id }: { id: string }) {
  const { isVisible, reveal } = useChartVisibility(id);
  return (
    <div>
      <span data-testid="visible">{String(isVisible("views"))}</span>
      <button type="button" onClick={() => reveal(["views"])}>
        reveal
      </button>
    </div>
  );
}

describe("chartVisibility", () => {
  it("publishes, subscribes, resets, and exposes the hook helpers", () => {
    resetChartVisibilityStore();
    expect(readChartVisibility("chart-a")).toBeNull();
    const seen: string[] = [];
    const stop = subscribeChartVisibility("chart-a", () => seen.push("n"));
    publishChartVisibility("chart-a", { views: false });
    expect(readChartVisibility("chart-a")).toEqual({ views: false });
    expect(seen).toEqual(["n"]);
    stop();
    // Last subscriber removal clears the entry.
    expect(readChartVisibility("chart-a")).toBeNull();

    publishChartVisibility("chart-b", { views: false });
    resetChartVisibilityStore();
    expect(readChartVisibility("chart-b")).toBeNull();

    render(<Probe id="chart-c" />);
    expect(screen.getByTestId("visible")).toHaveTextContent("true");
    screen.getByRole("button", { name: "reveal" }).click();
    expect(readChartVisibility("chart-c")).toEqual({ views: true });
  });

  it("uses the server snapshot when rendering on the server", () => {
    resetChartVisibilityStore();
    publishChartVisibility("ssr", { views: false });
    const html = renderToString(<Probe id="ssr" />);
    expect(html).toContain("true");
  });
});
