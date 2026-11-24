import { describe, expect, it, vi } from "vitest";
import { api, ApiError } from "./api";

describe("api", () => {
  it("parses json success and empty bodies", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: () => JSON.stringify({ id: 1 }),
    });
    vi.stubGlobal("fetch", fetchMock);
    await expect(api<{ id: number }>("/api/v1/admin/x")).resolves.toEqual({ id: 1 });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/admin/x",
      expect.objectContaining({ credentials: "include" }),
    );

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: true,
        status: 204,
        text: () => "",
      }),
    );
    document.cookie = "XSRF-TOKEN=token%2Fvalue";
    await expect(api("/api/v1/admin/x", { method: "DELETE" })).resolves.toBeUndefined();

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: true,
        status: 200,
        text: () => "",
      }),
    );
    await expect(api("/api/v1/admin/x")).resolves.toBeUndefined();
  });

  it("maps 401 and 403 to ApiError and sends CSRF", async () => {
    document.cookie = "XSRF-TOKEN=tok%2Fval";
    const fetchMock = vi.fn().mockResolvedValueOnce({
      ok: false,
      status: 401,
      statusText: "Unauthorized",
      json: () => ({ message: "Authentication required" }),
    });
    vi.stubGlobal("fetch", fetchMock);
    await expect(api("/api/v1/admin/x", { method: "POST", body: "{}" })).rejects.toEqual(
      expect.objectContaining({ status: 401 }) as ApiError,
    );
    const requestInit = fetchMock.mock.calls[0]?.[1] as RequestInit & { headers: Headers };
    expect(requestInit.headers.get("X-XSRF-TOKEN")).toBe("tok/val");

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: false,
        status: 403,
        statusText: "Forbidden",
        json: () => ({ message: "" }),
      }),
    );
    await expect(api("/api/v1/admin/x", { method: "PUT" })).rejects.toEqual(
      expect.objectContaining({ status: 403, message: "Admin role required" }) as ApiError,
    );

    document.cookie = "OTHER=1";
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: false,
        status: 401,
        statusText: "Unauthorized",
        json: () => ({ message: "" }),
      }),
    );
    await expect(api("/api/v1/admin/x", { method: "PATCH" })).rejects.toEqual(
      expect.objectContaining({ status: 401, message: "Sign in required" }) as ApiError,
    );
  });

  it("throws the server message or a fallback", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: false,
        status: 400,
        statusText: "Bad Request",
        json: () => ({ message: "No token" }),
      }),
    );
    await expect(api("/api/v1/admin/x")).rejects.toThrow("No token");

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: false,
        status: 500,
        statusText: "Server Error",
        json: () => {
          throw new Error("no json");
        },
      }),
    );
    await expect(api("/api/v1/admin/x")).rejects.toThrow("Server Error");

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: false,
        status: 502,
        statusText: "",
        json: () => ({}),
      }),
    );
    await expect(api("/api/v1/admin/x")).rejects.toThrow("Request failed");

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: false,
        status: 400,
        statusText: "Bad Request",
        json: () => ({ message: 42 }),
      }),
    );
    await expect(api("/api/v1/admin/x")).rejects.toThrow("Bad Request");

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: false,
        status: 400,
        statusText: "Bad Request",
        json: () => Promise.resolve({ message: "async body" }),
      }),
    );
    await expect(api("/api/v1/admin/x")).rejects.toThrow("async body");
  });
  it("omits account cookies on public reads", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, text: () => "{}" });
    vi.stubGlobal("fetch", fetchMock);
    await api("/api/v1/public/owners");
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/public/owners",
      expect.objectContaining({ credentials: "omit" }),
    );
  });
});
