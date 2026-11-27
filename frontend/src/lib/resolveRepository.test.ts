import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "./api";
import { repository } from "../test/fixtures";
import { emptyCursorPage } from "../test/repoRoutes";

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
      if (url.includes("/api/v1/owners")) return Promise.resolve(emptyCursorPage);
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
      "/api/v1/owners?login=KHolodilin&limit=1",
      "/api/v1/public/resolve?path=%2FKHolodilin%2Fspring-transactional-outbox-kafka",
      "/api/v1/admin/repositories/13",
    ]);
  });

  it("falls back to the admin list only when public resolve misses", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/api/v1/owners")) return Promise.resolve(emptyCursorPage);
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
      if (url.includes("/api/v1/owners")) return Promise.resolve(emptyCursorPage);
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

  it("rethrows unexpected catalog failures", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/api/v1/owners")) {
        return Promise.reject(new ApiError(500, "catalog down"));
      }
      return Promise.reject(new Error(`unexpected ${url}`));
    });
    await expect(
      resolveRepository("KHolodilin", "spring-transactional-outbox-kafka"),
    ).rejects.toMatchObject({ status: 500, message: "catalog down" });
  });

  it("rethrows unexpected public resolve failures after a catalog miss", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/api/v1/owners")) return Promise.resolve(emptyCursorPage);
      if (url.startsWith("/api/v1/public/resolve")) {
        return Promise.reject(new ApiError(503, "resolve unavailable"));
      }
      return Promise.reject(new Error(`unexpected ${url}`));
    });
    await expect(
      resolveRepository("KHolodilin", "spring-transactional-outbox-kafka"),
    ).rejects.toMatchObject({ status: 503, message: "resolve unavailable" });
  });

  it("prefers catalog owner and repository filters before public resolve", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.startsWith("/api/v1/owners?login=")) {
        return Promise.resolve({
          items: [{ ownerId: "own-1", login: "KHolodilin" }],
          nextCursor: null,
        });
      }
      if (url.includes("/api/v1/owners/own-1/repositories?name=")) {
        return Promise.resolve({
          items: [
            {
              repositoryId: "13",
              ownerId: "own-1",
              name: repository.name,
              fullName: repository.fullName,
              description: null,
              language: "Java",
              archived: false,
              fork: false,
              activityStatus: "ACTIVE",
              modules: {
                dashboard: true,
                traffic: true,
                referrers: true,
                paths: true,
                topics: true,
              },
            },
          ],
          nextCursor: null,
        });
      }
      if (url === "/api/v1/admin/repositories/13") return Promise.resolve(repository);
      return Promise.reject(new Error(`unexpected ${url}`));
    });

    await expect(
      resolveRepository("KHolodilin", "spring-transactional-outbox-kafka"),
    ).resolves.toEqual(repository);
    expect(
      api.mock.calls.map(([path]) => String(path)).some((path) => path.includes("/public/resolve")),
    ).toBe(false);
  });
});
