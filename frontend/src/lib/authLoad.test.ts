import { describe, expect, it, vi } from "vitest";
import { loadMe, signOut } from "./auth";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("./api", () => ({ api }));

describe("auth load/signOut", () => {
  it("loads me and signs out", async () => {
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
    api.mockResolvedValueOnce(undefined);
    await expect(signOut("/logout")).resolves.toBeUndefined();
    expect(api).toHaveBeenCalledWith("/logout", { method: "POST" });
  });
});
