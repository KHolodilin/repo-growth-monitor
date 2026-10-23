import { afterEach, describe, expect, it } from "vitest";
import {
  defaultSearchQueryIds,
  readSearchQuerySelection,
  resolveSearchQuerySelection,
  searchQuerySelectionCookie,
  writeSearchQuerySelection,
} from "./searchQuerySelection";

afterEach(() => {
  document.cookie = `${searchQuerySelectionCookie(13)}=; path=/; max-age=0`;
  document.cookie = `${searchQuerySelectionCookie(8)}=; path=/; max-age=0`;
});

describe("search query chart selection", () => {
  it("picks the five best ranks and breaks ties by id", () => {
    expect(defaultSearchQueryIds([
      { id: 9, rank: 4 },
      { id: 2, rank: 1 },
      { id: 3, rank: 1 },
      { id: 4, rank: null },
      { id: 5, rank: 2 },
      { id: 6, rank: 3 },
      { id: 7, rank: 8 },
    ])).toEqual([2, 3, 5, 6, 9]);
  });

  it("stores an empty selection and does not refill it", () => {
    writeSearchQuerySelection(13, []);
    expect(readSearchQuerySelection(13)).toEqual([]);
    expect(resolveSearchQuerySelection(13, [{ id: 1, rank: 1 }, { id: 2, rank: 2 }])).toEqual([]);
  });

  it("drops deleted ids and saves the rest", () => {
    writeSearchQuerySelection(13, [1, 99]);
    expect(resolveSearchQuerySelection(13, [{ id: 1, rank: 4 }, { id: 2, rank: 1 }])).toEqual([1]);
    expect(readSearchQuerySelection(13)).toEqual([1]);
  });

  it("selects the best ranks when the cookie is missing", () => {
    expect(resolveSearchQuerySelection(8, [
      { id: 4, rank: 9 },
      { id: 1, rank: 2 },
    ])).toEqual([1, 4]);
    expect(readSearchQuerySelection(8)).toEqual([1, 4]);
  });

  it("orders two unranked queries by id and sets Secure on https", () => {
    expect(defaultSearchQueryIds([
      { id: 3, rank: null },
      { id: 1, rank: null },
    ])).toEqual([1, 3]);
    const assigned: string[] = [];
    const descriptor = Object.getOwnPropertyDescriptor(Document.prototype, "cookie");
    Object.defineProperty(document, "cookie", {
      configurable: true,
      get: () => descriptor?.get?.call(document) ?? "",
      set: (value: string) => {
        assigned.push(value);
        descriptor?.set?.call(document, value);
      },
    });
    try {
      writeSearchQuerySelection(13, [4], "https:");
      expect(assigned.some((value) => value.includes("; Secure"))).toBe(true);
      expect(readSearchQuerySelection(13)).toEqual([4]);
    } finally {
      if (descriptor) {
        Object.defineProperty(document, "cookie", descriptor);
      }
    }
  });

  it("ignores a cookie that is not a list of ids", () => {
    document.cookie = `${searchQuerySelectionCookie(13)}=${encodeURIComponent("{")}; path=/`;
    expect(readSearchQuerySelection(13)).toBeNull();
    document.cookie = `${searchQuerySelectionCookie(13)}=${encodeURIComponent("[1,\"a\"]")}; path=/`;
    expect(readSearchQuerySelection(13)).toBeNull();
  });
});
