import { describe, expect, it, vi } from "vitest";
import { api, ApiError } from "./api";

describe("api", () => {
  it("parses json success and empty bodies", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ id: 1 }),
    });
    vi.stubGlobal("fetch", fetchMock);
    await expect(api<{ id: number }>("/api/v1/x")).resolves.toEqual({ id: 1 });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/x",
      expect.objectContaining({ credentials: "include" }),
    );

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: true,
        status: 204,
        text: async () => "",
      }),
    );
    document.cookie = "XSRF-TOKEN=token%2Fvalue";
    await expect(api("/api/v1/x", { method: "DELETE" })).resolves.toBeUndefined();

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: true,
        status: 200,
        text: async () => "",
      }),
    );
    await expect(api("/api/v1/x")).resolves.toBeUndefined();
  });

  it("maps 401 and 403 to ApiError", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: false,
        status: 401,
        statusText: "Unauthorized",
        json: async () => ({ message: "Authentication required" }),
      }),
    );
    await expect(api("/api/v1/x", { method: "POST" })).rejects.toEqual(
      expect.objectContaining({ status: 401 }) as ApiError,
    );
  });

  it("throws the server message or a fallback", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: false,
        status: 400,
        statusText: "Bad Request",
        json: async () => ({ message: "No token" }),
      }),
    );
    await expect(api("/api/v1/x")).rejects.toThrow("No token");

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: false,
        status: 500,
        statusText: "Server Error",
        json: async () => {
          throw new Error("no json");
        },
      }),
    );
    await expect(api("/api/v1/x")).rejects.toThrow("Server Error");

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: false,
        status: 502,
        statusText: "",
        json: async () => ({}),
      }),
    );
    await expect(api("/api/v1/x")).rejects.toThrow("Request failed");
  });
});
