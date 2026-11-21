import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { loadMe, signOut } from "./auth";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("./api", () => ({ api }));

describe("auth load/signOut", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("loads me and signs out without parsing HTML", async () => {
    api.mockResolvedValueOnce({
      authenticated: true,
      userId: "1",
      displayName: "Ada",
      roles: ["USER", "ADMIN"],
      actions: ["MUTATE"],
      loginUrl: "/login",
      logoutUrl: "/logout",
    });
    await expect(loadMe()).resolves.toMatchObject({ displayName: "Ada" });
    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: async () => "<!doctype html><html></html>",
    });
    await expect(signOut("/logout")).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledWith(
      "/logout",
      expect.objectContaining({ method: "POST", credentials: "include" }),
    );
    expect(api).not.toHaveBeenCalledWith("/logout", expect.anything());
  });
});
