import { beforeEach, describe, expect, it, vi } from "vitest";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("./api", async (original) => ({
  ...(await original<typeof import("./api")>()),
  api,
}));

import {
  findOwnerByLogin,
  findRepositoryByName,
  listCatalogRepositoriesForSwitcher,
} from "./catalogApi";

describe("catalogApi", () => {
  beforeEach(() => {
    api.mockReset();
  });

  it("returns null when exact login or name filters miss", async () => {
    api.mockResolvedValueOnce({ items: [], nextCursor: null });
    await expect(findOwnerByLogin("Missing")).resolves.toBeNull();
    api.mockResolvedValueOnce({ items: [], nextCursor: null });
    await expect(findRepositoryByName("1", "ghost")).resolves.toBeNull();
  });

  it("returns the first exact catalog card", async () => {
    api.mockResolvedValueOnce({
      items: [{ ownerId: "1", login: "Alpha" }],
      nextCursor: null,
    });
    await expect(findOwnerByLogin("Alpha")).resolves.toEqual({ ownerId: "1", login: "Alpha" });
    api.mockResolvedValueOnce({
      items: [
        {
          repositoryId: "13",
          ownerId: "1",
          name: "project",
          fullName: "Alpha/project",
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
    await expect(findRepositoryByName("1", "project")).resolves.toMatchObject({
      repositoryId: "13",
      name: "project",
    });
  });

  it("lists catalog repositories for the switcher across owner pages", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url === "/api/v1/owners?limit=100") {
        return Promise.resolve({
          items: [{ ownerId: "1", login: "Alpha" }],
          nextCursor: "2",
        });
      }
      if (url === "/api/v1/owners?limit=100&cursor=2") {
        return Promise.resolve({
          items: [{ ownerId: "2", login: "Beta" }],
          nextCursor: null,
        });
      }
      if (url === "/api/v1/owners/1/repositories?limit=100") {
        return Promise.resolve({
          items: [
            {
              repositoryId: "",
              ownerId: "1",
              name: "",
              fullName: "Alpha/",
              description: null,
              language: null,
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
            {
              repositoryId: "13",
              ownerId: "1",
              name: "project",
              fullName: "Alpha/project",
              description: "desc",
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
          nextCursor: "14",
        });
      }
      if (url === "/api/v1/owners/1/repositories?limit=100&cursor=14") {
        return Promise.resolve({
          items: [
            {
              repositoryId: "14",
              ownerId: "1",
              name: "second",
              fullName: "Alpha/second",
              description: null,
              language: null,
              archived: false,
              fork: false,
              activityStatus: undefined as unknown as string,
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
      if (url === "/api/v1/owners/2/repositories?limit=100") {
        return Promise.resolve({
          items: [
            {
              repositoryId: "8",
              ownerId: "2",
              name: "other",
              fullName: "Beta/other",
              description: null,
              language: null,
              archived: true,
              fork: true,
              activityStatus: "ARCHIVED",
              modules: {
                dashboard: true,
                traffic: false,
                referrers: false,
                paths: false,
                topics: false,
              },
            },
          ],
          nextCursor: null,
        });
      }
      return Promise.reject(new Error(`unexpected ${url}`));
    });

    await expect(listCatalogRepositoriesForSwitcher()).resolves.toEqual([
      expect.objectContaining({
        id: 13,
        name: "project",
        fullName: "Alpha/project",
        trackingEnabled: true,
        owner: expect.objectContaining({ id: 1, login: "Alpha" }),
      }),
      expect.objectContaining({
        id: 14,
        name: "second",
        activityStatus: null,
        owner: expect.objectContaining({ id: 1, login: "Alpha" }),
      }),
      expect.objectContaining({
        id: 8,
        name: "other",
        archived: true,
        fork: true,
        owner: expect.objectContaining({ id: 2, login: "Beta" }),
      }),
    ]);
  });
});
