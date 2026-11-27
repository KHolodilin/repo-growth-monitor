import { beforeEach, describe, expect, it, vi } from "vitest";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("./api", async (original) => ({
  ...(await original<typeof import("./api")>()),
  api,
}));

import { findOwnerByLogin, findRepositoryByName } from "./catalogApi";

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
});
