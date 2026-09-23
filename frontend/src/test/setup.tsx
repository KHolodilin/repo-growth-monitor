import "@testing-library/jest-dom/vitest";
import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";

vi.mock("echarts-for-react", () => ({
  default: ({
    onEvents,
  }: {
    onEvents?: Record<string, (params: Record<string, unknown>) => void>;
  }) => (
    <div data-testid="echart">
      <button
        type="button"
        onClick={() => onEvents?.legendselectchanged?.({ selected: { Views: false, Visitors: true } })}
      >
        legend
      </button>
      <button
        type="button"
        onClick={() =>
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
          })
        }
      >
        mark
      </button>
    </div>
  ),
}));

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
