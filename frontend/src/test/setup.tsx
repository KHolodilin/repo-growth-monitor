import "@testing-library/jest-dom/vitest";
import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";

vi.mock("echarts-for-react", async () => {
  const React = await import("react");
  const MockECharts = React.forwardRef(function MockECharts(
    {
      onEvents,
    }: {
      onEvents?: Record<string, (params: Record<string, unknown>) => void>;
    },
    ref: React.ForwardedRef<{ getEchartsInstance: () => { getDataURL: () => string } }>,
  ) {
    React.useImperativeHandle(ref, () => ({
      getEchartsInstance: () => ({
        getDataURL: () => "data:image/png;base64,AAAA",
      }),
    }));
    return React.createElement(
      "div",
      { "data-testid": "echart" },
      React.createElement(
        "button",
        {
          type: "button",
          onClick: () => onEvents?.legendselectchanged?.({ selected: { Views: false, Visitors: true } }),
        },
        "legend",
      ),
      React.createElement(
        "button",
        { type: "button", onClick: () => onEvents?.legendselectchanged?.({}) },
        "legend-empty",
      ),
      React.createElement(
        "button",
        {
          type: "button",
          onClick: () =>
            onEvents?.click?.({
              componentType: "markLine",
              data: {
                events: [
                  {
                    id: 1,
                    repositoryId: 13,
                    eventAt: "2026-09-22T12:00:00Z",
                    category: "PROMOTION",
                    type: "LINKEDIN_POST",
                    title: "LinkedIn launch",
                    source: "MANUAL",
                  },
                ],
              },
            }),
        },
        "mark",
      ),
    );
  });
  return { default: MockECharts };
});

Element.prototype.scrollIntoView = vi.fn();

afterEach(() => {
  cleanup();
  document.cookie.split(";").forEach((part) => {
    const name = part.split("=")[0]?.trim();
    if (name) {
      document.cookie = `${name}=; path=/; max-age=0`;
    }
  });
});
