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

  it("falls back to the admin list when catalog misses", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/api/v1/owners")) return Promise.resolve(emptyCursorPage);
      if (url.includes("/api/v1/admin/repositories?refresh=false"))
        return Promise.resolve([repository]);
      return Promise.reject(new Error(`unexpected ${url}`));
    });

    await expect(
      resolveRepository("KHolodilin", "spring-transactional-outbox-kafka"),
    ).resolves.toEqual(repository);
    expect(api).toHaveBeenCalledWith("/api/v1/admin/repositories?refresh=false");
  });

  it("returns not found when catalog finds the owner but not the repository", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.startsWith("/api/v1/owners?login=")) {
        return Promise.resolve({
          items: [{ ownerId: "1", login: "Alpha" }],
          nextCursor: null,
        });
      }
      if (url.includes("/repositories?name=")) {
        return Promise.resolve({ items: [], nextCursor: null });
      }
      if (url.includes("/api/v1/admin/repositories?refresh=false")) {
        return Promise.resolve([]);
      }
      return Promise.reject(new Error(`unexpected ${url}`));
    });
    await expect(resolveRepository("Alpha", "missing")).rejects.toMatchObject({
      status: 404,
      message: "Repository not found",
    });
  });

  it("returns not found when catalog misses and the admin list is closed", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/api/v1/owners")) return Promise.resolve(emptyCursorPage);
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

  it("continues after catalog 401 and matches admin list by owner/name", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/api/v1/owners?login=")) {
        return Promise.reject(new ApiError(401, "Authentication required"));
      }
      if (url.includes("/api/v1/admin/repositories?refresh=false")) {
        return Promise.resolve([
          {
            ...repository,
            fullName: "Other/name",
            owner: { ...repository.owner, login: "KHolodilin" },
            name: "spring-transactional-outbox-kafka",
          },
        ]);
      }
      return Promise.reject(new Error(`unexpected ${url}`));
    });
    await expect(
      resolveRepository("KHolodilin", "spring-transactional-outbox-kafka"),
    ).resolves.toMatchObject({ name: "spring-transactional-outbox-kafka" });
  });

  it("prefers catalog owner and repository filters", async () => {
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
      if (url === "/api/v1/repositories/13") return Promise.resolve(repository);
      return Promise.reject(new Error(`unexpected ${url}`));
    });

    await expect(
      resolveRepository("KHolodilin", "spring-transactional-outbox-kafka"),
    ).resolves.toEqual(repository);
    expect(
      api.mock.calls.map(([path]) => String(path)).some((path) => path.includes("/public/")),
    ).toBe(false);
  });
});
