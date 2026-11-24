import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { growthEvent } from "../test/fixtures";
import { useRepositoryGrowthEvents } from "./useRepositoryGrowthEvents";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("./api", () => ({ api }));

function Probe({ repositoryId, period }: { repositoryId: number; period: string }) {
  const { events, error, loading, reload } = useRepositoryGrowthEvents(repositoryId, period);
  return (
    <div>
      <span data-testid="loading">{String(loading)}</span>
      <span data-testid="error">{error ?? ""}</span>
      <span data-testid="count">{events.length}</span>
      <span data-testid="titles">{events.map((event) => event.title).join(",")}</span>
      <button type="button" onClick={() => reload()}>
        reload
      </button>
    </div>
  );
}

afterEach(() => cleanup());
beforeEach(() => {
  api.mockReset();
});

describe("useRepositoryGrowthEvents", () => {
  it("keeps the newer period result when responses arrive out of order", async () => {
    let finish30: (value: unknown) => void = () => undefined;
    let finish7: (value: unknown) => void = () => undefined;
    api.mockImplementation((path: unknown) => {
      const url = String(path);
      if (url.includes("period=30d")) {
        return new Promise((resolve) => {
          finish30 = resolve;
        });
      }
      if (url.includes("period=7d")) {
        return new Promise((resolve) => {
          finish7 = resolve;
        });
      }
      return Promise.reject(new Error(`unexpected ${url}`));
    });

    const { rerender } = render(<Probe repositoryId={13} period="30d" />);
    expect(screen.getByTestId("loading")).toHaveTextContent("true");
    expect(api).toHaveBeenCalled();

    rerender(<Probe repositoryId={13} period="7d" />);
    expect(screen.getByTestId("count")).toHaveTextContent("0");

    finish7([{ ...growthEvent, id: 7, title: "week event" }]);
    await waitFor(() => expect(screen.getByTestId("titles")).toHaveTextContent("week event"));
    expect(screen.getByTestId("loading")).toHaveTextContent("false");

    finish30([{ ...growthEvent, id: 30, title: "month event" }]);
    await waitFor(() => expect(screen.getByTestId("titles")).toHaveTextContent("week event"));
  });

  it("ignores a stale error after a newer success", async () => {
    let fail30: (reason: unknown) => void = () => undefined;
    let finish7: (value: unknown) => void = () => undefined;
    api.mockImplementation((path: unknown) => {
      const url = String(path);
      if (url.includes("period=30d")) {
        return new Promise((_, reject) => {
          fail30 = reject;
        });
      }
      if (url.includes("period=7d")) {
        return new Promise((resolve) => {
          finish7 = resolve;
        });
      }
      return Promise.reject(new Error(`unexpected ${url}`));
    });

    const { rerender } = render(<Probe repositoryId={13} period="30d" />);
    rerender(<Probe repositoryId={13} period="7d" />);
    finish7([{ ...growthEvent, title: "week event" }]);
    await waitFor(() => expect(screen.getByTestId("titles")).toHaveTextContent("week event"));

    fail30(new Error("stale period error"));
    await waitFor(() => expect(screen.getByTestId("error")).toHaveTextContent(""));
    expect(screen.getByTestId("titles")).toHaveTextContent("week event");
  });

  it("shows a load error without pretending the list is empty success", async () => {
    api.mockRejectedValueOnce(new Error("events offline"));
    render(<Probe repositoryId={13} period="30d" />);
    await waitFor(() => expect(screen.getByTestId("error")).toHaveTextContent("events offline"));
    expect(screen.getByTestId("count")).toHaveTextContent("0");
    expect(screen.getByTestId("loading")).toHaveTextContent("false");
  });

  it("stringifies non-Error load failures", async () => {
    api.mockRejectedValueOnce("events down");
    render(<Probe repositoryId={13} period="30d" />);
    await waitFor(() => expect(screen.getByTestId("error")).toHaveTextContent("events down"));
    expect(screen.getByTestId("count")).toHaveTextContent("0");
  });

  it("clears a previous error when reload succeeds", async () => {
    let calls = 0;
    api.mockImplementation(() => {
      calls += 1;
      if (calls === 1) {
        // eslint-disable-next-line @typescript-eslint/prefer-promise-reject-errors -- non-Error path
        return Promise.reject("first down");
      }
      return Promise.resolve([{ ...growthEvent, title: "recovered" }]);
    });
    render(<Probe repositoryId={13} period="30d" />);
    await waitFor(() => expect(screen.getByTestId("error")).toHaveTextContent("first down"));
    screen.getByRole("button", { name: "reload" }).click();
    await waitFor(() => expect(screen.getByTestId("titles")).toHaveTextContent("recovered"));
    expect(screen.getByTestId("error")).toHaveTextContent("");
  });

  it("keeps prior events when a same-context reload fails after success", async () => {
    let failReload: (reason: unknown) => void = () => undefined;
    let calls = 0;
    api.mockImplementation(() => {
      calls += 1;
      if (calls === 1) {
        return Promise.resolve([{ ...growthEvent, title: "kept event" }]);
      }
      return new Promise((_, reject) => {
        failReload = reject;
      });
    });

    render(<Probe repositoryId={13} period="30d" />);
    await waitFor(() => expect(screen.getByTestId("titles")).toHaveTextContent("kept event"));
    screen.getByRole("button", { name: "reload" }).click();
    expect(screen.getByTestId("titles")).toHaveTextContent("kept event");
    failReload(new Error("refresh failed"));
    await waitFor(() => expect(screen.getByTestId("error")).toHaveTextContent("refresh failed"));
    expect(screen.getByTestId("titles")).toHaveTextContent("kept event");
  });

  it("ignores a stale reload error after a newer reload has started", async () => {
    const rejectors: Array<(reason: unknown) => void> = [];
    api.mockImplementation(() => {
      if (rejectors.length === 0) {
        return Promise.resolve([{ ...growthEvent, title: "base" }]);
      }
      return new Promise((_, reject) => {
        rejectors.push(reject);
      });
    });
    render(<Probe repositoryId={13} period="30d" />);
    await waitFor(() => expect(screen.getByTestId("titles")).toHaveTextContent("base"));
    // Prime rejectors list for subsequent reloads.
    api.mockImplementation(
      () =>
        new Promise((_, reject) => {
          rejectors.push(reject);
        }),
    );
    screen.getByRole("button", { name: "reload" }).click();
    await waitFor(() => expect(rejectors.length).toBe(1));
    screen.getByRole("button", { name: "reload" }).click();
    await waitFor(() => expect(rejectors.length).toBe(2));
    rejectors[0](new Error("stale reload error"));
    await waitFor(() => expect(screen.getByTestId("error")).toHaveTextContent(""));
    expect(screen.getByTestId("titles")).toHaveTextContent("base");
    rejectors[1]("latest reload error");
    await waitFor(() =>
      expect(screen.getByTestId("error")).toHaveTextContent("latest reload error"),
    );
  });

  it("ignores overlapping onChanged responses that finish out of order", async () => {
    const resolvers: Array<(value: unknown) => void> = [];
    api.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolvers.push(resolve);
        }),
    );

    render(<Probe repositoryId={13} period="30d" />);
    await waitFor(() => expect(resolvers.length).toBeGreaterThanOrEqual(1));
    screen.getByRole("button", { name: "reload" }).click();
    await waitFor(() => expect(resolvers.length).toBeGreaterThanOrEqual(2));
    screen.getByRole("button", { name: "reload" }).click();
    await waitFor(() => expect(resolvers.length).toBeGreaterThanOrEqual(3));

    expect(resolvers.length).toBeGreaterThanOrEqual(3);
    const resolveThird = resolvers[resolvers.length - 1];
    const resolveSecond = resolvers[1];
    const resolveFirst = resolvers[0];
    if (!resolveThird || !resolveSecond || !resolveFirst) {
      throw new Error("expected three controlled resolvers");
    }
    resolveThird([{ ...growthEvent, title: "third" }]);
    await waitFor(() => expect(screen.getByTestId("titles")).toHaveTextContent("third"));
    resolveSecond([{ ...growthEvent, title: "second" }]);
    await waitFor(() => expect(screen.getByTestId("titles")).toHaveTextContent("third"));
    resolveFirst([{ ...growthEvent, title: "first" }]);
    await waitFor(() => expect(screen.getByTestId("titles")).toHaveTextContent("third"));
  });

  it("does not apply a result after unmount", async () => {
    let finish: (value: unknown) => void = () => undefined;
    api.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const view = render(<Probe repositoryId={13} period="30d" />);
    view.unmount();
    finish([{ ...growthEvent, title: "late" }]);
    await waitFor(() => {
      expect(screen.queryByTestId("titles")).not.toBeInTheDocument();
    });
  });

  it("treats a successful empty payload as zero events without an error", async () => {
    api.mockResolvedValueOnce([]);
    render(<Probe repositoryId={13} period="30d" />);
    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));
    expect(screen.getByTestId("count")).toHaveTextContent("0");
    expect(screen.getByTestId("error")).toHaveTextContent("");
  });

  it("aborts the current reload request on unmount", async () => {
    const signals: AbortSignal[] = [];
    let calls = 0;
    api.mockImplementation((_path: unknown, init?: RequestInit) => {
      calls += 1;
      if (init?.signal) {
        signals.push(init.signal);
      }
      if (calls === 1) {
        return Promise.resolve([{ ...growthEvent, title: "base" }]);
      }
      return new Promise(() => undefined);
    });

    const view = render(<Probe repositoryId={13} period="30d" />);
    await waitFor(() => expect(screen.getByTestId("titles")).toHaveTextContent("base"));
    screen.getByRole("button", { name: "reload" }).click();
    await waitFor(() => expect(signals.length).toBe(2));
    view.unmount();
    expect(signals[1]?.aborted).toBe(true);
  });

  it("aborts an in-flight reload when the context changes", async () => {
    const signals: AbortSignal[] = [];
    let calls = 0;
    api.mockImplementation((path: unknown, init?: RequestInit) => {
      calls += 1;
      if (init?.signal) {
        signals.push(init.signal);
      }
      const url = String(path);
      if (calls === 1 && url.includes("period=30d")) {
        return Promise.resolve([{ ...growthEvent, title: "base" }]);
      }
      return new Promise(() => undefined);
    });

    const { rerender } = render(<Probe repositoryId={13} period="30d" />);
    await waitFor(() => expect(screen.getByTestId("titles")).toHaveTextContent("base"));
    screen.getByRole("button", { name: "reload" }).click();
    await waitFor(() => expect(signals.length).toBe(2));
    expect(signals[1]?.aborted).toBe(false);
    rerender(<Probe repositoryId={13} period="7d" />);
    await waitFor(() => expect(signals.length).toBeGreaterThanOrEqual(3));
    expect(signals[1]?.aborted).toBe(true);
    expect(screen.getByTestId("count")).toHaveTextContent("0");
    expect(screen.getByTestId("loading")).toHaveTextContent("true");
  });

  it("ignores a stale finally after a newer request has started", async () => {
    const resolvers: Array<(value: unknown) => void> = [];
    api.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolvers.push(resolve);
        }),
    );
    render(<Probe repositoryId={13} period="30d" />);
    await waitFor(() => expect(resolvers.length).toBe(1));
    screen.getByRole("button", { name: "reload" }).click();
    await waitFor(() => expect(resolvers.length).toBe(2));
    expect(screen.getByTestId("loading")).toHaveTextContent("true");
    resolvers[0]?.([{ ...growthEvent, title: "stale" }]);
    await waitFor(() => expect(screen.getByTestId("titles")).toHaveTextContent(""));
    expect(screen.getByTestId("loading")).toHaveTextContent("true");
    resolvers[1]?.([{ ...growthEvent, title: "fresh" }]);
    await waitFor(() => expect(screen.getByTestId("titles")).toHaveTextContent("fresh"));
    expect(screen.getByTestId("loading")).toHaveTextContent("false");
  });
});
