import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { waitForCollectionRequest } from "./collectionRequest";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("./api", () => ({ api }));

describe("waitForCollectionRequest", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    api.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("returns when the request leaves ACTIVE", async () => {
    api.mockResolvedValueOnce({ state: "ACTIVE" }).mockResolvedValueOnce({ state: "SUCCEEDED" });
    const done = waitForCollectionRequest("req-1", { intervalMs: 10 });
    await vi.advanceTimersByTimeAsync(10);
    await expect(done).resolves.toEqual({ state: "SUCCEEDED" });
    expect(api).toHaveBeenCalledTimes(2);
    expect(api).toHaveBeenCalledWith("/api/v1/collection-requests/req-1", expect.any(Object));
  });

  it("rejects with the abort Error reason while waiting between polls", async () => {
    api.mockResolvedValue({ state: "ACTIVE" });
    const abort = new AbortController();
    const done = waitForCollectionRequest("req-2", { intervalMs: 50, signal: abort.signal });
    await Promise.resolve();
    abort.abort(new Error("stop collection"));
    await expect(done).rejects.toThrow("stop collection");
  });

  it("rejects with AbortError when the abort reason is not an Error", async () => {
    api.mockResolvedValue({ state: "ACTIVE" });
    const abort = new AbortController();
    const done = waitForCollectionRequest("req-3", { intervalMs: 50, signal: abort.signal });
    await Promise.resolve();
    abort.abort("cancelled");
    await expect(done).rejects.toMatchObject({ name: "AbortError" });
  });
});
