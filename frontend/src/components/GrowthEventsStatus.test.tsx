import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { canShowGrowthEventsList, GrowthEventsStatus } from "./GrowthEventsPanel";

afterEach(() => cleanup());

describe("GrowthEventsStatus", () => {
  it("shows loading on the first fetch instead of an empty success message", () => {
    render(<GrowthEventsStatus loading error={null} hasEvents={false} onRetry={vi.fn()} />);
    expect(screen.getByRole("status")).toHaveTextContent(/Loading growth events/);
    expect(screen.queryByText(/No growth events/)).not.toBeInTheDocument();
  });

  it("shows an error with retry and never claims the list is empty", async () => {
    const onRetry = vi.fn();
    const user = userEvent.setup();
    render(
      <GrowthEventsStatus
        loading={false}
        error="events offline"
        hasEvents={false}
        onRetry={onRetry}
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("events offline");
    expect(screen.queryByText(/No growth events/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("keeps prior events visible while a refresh error is shown", () => {
    render(
      <GrowthEventsStatus loading={false} error="refresh failed" hasEvents onRetry={vi.fn()} />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("refresh failed");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
});

describe("canShowGrowthEventsList", () => {
  it("hides the empty list while loading or failed without rows", () => {
    expect(canShowGrowthEventsList(true, null, false)).toBe(false);
    expect(canShowGrowthEventsList(false, "boom", false)).toBe(false);
    expect(canShowGrowthEventsList(false, null, false)).toBe(true);
    expect(canShowGrowthEventsList(true, "boom", true)).toBe(true);
  });
});
