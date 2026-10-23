import { afterEach, describe, expect, it } from "vitest";
import {
  defaultTopicSlugs,
  readTopicSelection,
  resolveTopicSelection,
  topicSelectionCookie,
  writeTopicSelection,
} from "./topicSelection";

afterEach(() => {
  document.cookie = `${topicSelectionCookie(13)}=; path=/; max-age=0`;
});

describe("topic chart selection", () => {
  it("picks the five best ranks and breaks ties by slug", () => {
    expect(defaultTopicSlugs([
      { slug: "zeta", rank: 4 },
      { slug: "beta", rank: 1 },
      { slug: "alpha", rank: 1 },
      { slug: "none", rank: null },
      { slug: "gamma", rank: 2 },
      { slug: "delta", rank: 3 },
      { slug: "epsilon", rank: 8 },
    ])).toEqual(["alpha", "beta", "gamma", "delta", "zeta"]);
  });

  it("stores an empty selection and does not refill it", () => {
    writeTopicSelection(13, []);
    expect(readTopicSelection(13)).toEqual([]);
    expect(resolveTopicSelection(13, [{ slug: "alpha", rank: 1 }])).toEqual([]);
  });

  it("drops unknown slugs and saves the rest", () => {
    writeTopicSelection(13, ["alpha", "gone"]);
    expect(resolveTopicSelection(13, [{ slug: "alpha", rank: 4 }, { slug: "beta", rank: 1 }])).toEqual(["alpha"]);
    expect(readTopicSelection(13)).toEqual(["alpha"]);
  });

  it("selects the best ranks when the cookie is missing", () => {
    expect(resolveTopicSelection(13, [{ slug: "beta", rank: 2 }, { slug: "alpha", rank: 1 }])).toEqual(["alpha", "beta"]);
    expect(readTopicSelection(13)).toEqual(["alpha", "beta"]);
  });

  it("treats an unparseable cookie as missing", () => {
    document.cookie = `${topicSelectionCookie(13)}=${encodeURIComponent("{")}; path=/`;
    expect(readTopicSelection(13)).toBeNull();
    document.cookie = `${topicSelectionCookie(13)}=${encodeURIComponent("[1]")}; path=/`;
    expect(readTopicSelection(13)).toBeNull();
  });

  it("orders two missing ranks by slug", () => {
    expect(defaultTopicSlugs([
      { slug: "beta", rank: null },
      { slug: "alpha", rank: null },
    ])).toEqual(["alpha", "beta"]);
  });

  it("sets Secure on https", () => {
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
      writeTopicSelection(13, ["alpha"], "https:");
      expect(assigned.some((value) => value.includes("; Secure"))).toBe(true);
      expect(readTopicSelection(13)).toEqual(["alpha"]);
    } finally {
      if (descriptor) {
        Object.defineProperty(document, "cookie", descriptor);
      }
    }
  });
});
