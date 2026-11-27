import { describe, expect, it } from "vitest";
import { repository } from "./fixtures";
import { respondCatalogSwitcher, isOwnersCatalog } from "./repoRoutes";

describe("repoRoutes catalog switcher", () => {
  it("lists owners and repositories for switcher urls", () => {
    const other = { ...repository, id: 8, name: "other", fullName: "KHolodilin/other" };
    expect(respondCatalogSwitcher("/api/v1/owners?limit=100", [repository, other])).toEqual({
      items: [{ ownerId: "1", login: repository.owner.login }],
      nextCursor: null,
    });
    expect(
      respondCatalogSwitcher(`/api/v1/owners/${repository.owner.id}/repositories?limit=100`, [
        repository,
        other,
      ])?.items.map((row) => row.name),
    ).toEqual([repository.name, "other"]);
  });

  it("ignores non-switcher catalog urls", () => {
    expect(respondCatalogSwitcher("/api/v1/owners?login=Alpha", [repository])).toBeNull();
    expect(isOwnersCatalog("/api/v1/owners?limit=100")).toBe(false);
    expect(isOwnersCatalog("/api/v1/owners")).toBe(true);
  });
});
