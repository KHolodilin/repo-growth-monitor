import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "./api";
import { repository } from "../test/fixtures";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("./api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api")>();
  return { ...actual, api };
});

import { resolveRepository } from "./resolveRepository";

describe("resolveRepository", () => {
  beforeEach(() => {
    api.mockReset();
  });

  it("resolves published paths through public resolve without listing admin repositories", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.startsWith("/api/v1/public/resolve")) {
        return Promise.resolve({
          ownerId: "1",
          repositoryId: "13",
          canonicalPath: "/KHolodilin/spring-transactional-outbox-kafka",
          redirect: false,
        });
      }
      if (url === "/api/v1/admin/repositories/13") return Promise.resolve(repository);
      return Promise.reject(new Error(`unexpected ${url}`));
    });

    await expect(
      resolveRepository("KHolodilin", "spring-transactional-outbox-kafka"),
    ).resolves.toEqual(repository);
    expect(api.mock.calls.map(([path]) => String(path))).toEqual([
      "/api/v1/public/resolve?path=%2FKHolodilin%2Fspring-transactional-outbox-kafka",
      "/api/v1/admin/repositories/13",
    ]);
  });

  it("falls back to the admin list only when public resolve misses", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.startsWith("/api/v1/public/resolve"))
        return Promise.reject(new ApiError(404, "Page not found"));
      if (url.includes("/api/v1/admin/repositories?refresh=false"))
        return Promise.resolve([repository]);
      return Promise.reject(new Error(`unexpected ${url}`));
    });

    await expect(
      resolveRepository("KHolodilin", "spring-transactional-outbox-kafka"),
    ).resolves.toEqual(repository);
    expect(api).toHaveBeenCalledWith("/api/v1/admin/repositories?refresh=false");
  });

  it("returns not found when public resolve misses and the admin list is closed", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.startsWith("/api/v1/public/resolve"))
        return Promise.reject(new ApiError(404, "Page not found"));
      if (url.includes("/api/v1/admin/repositories?refresh=false"))
        return Promise.reject(new ApiError(401, "Authentication required"));
      return Promise.reject(new Error(`unexpected ${url}`));
    });

    await expect(resolveRepository("Hidden", "secret")).rejects.toMatchObject({
      status: 404,
      message: "Repository not found",
    });
  });
});
