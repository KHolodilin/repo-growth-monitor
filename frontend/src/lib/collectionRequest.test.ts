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
    api
      .mockResolvedValueOnce({ state: "ACTIVE" })
      .mockResolvedValueOnce({ state: "SUCCEEDED" });
    const done = waitForCollectionRequest("req-1", { intervalMs: 10 });
    await vi.advanceTimersByTimeAsync(10);
    await expect(done).resolves.toEqual({ state: "SUCCEEDED" });
    expect(api).toHaveBeenCalledTimes(2);
    expect(api).toHaveBeenCalledWith("/api/v1/admin/collection-requests/req-1", expect.any(Object));
  });
});
