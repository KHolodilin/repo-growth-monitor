import { afterEach, describe, expect, it } from "vitest";
import { readTopicScope, resolveVisibleScope, topicScopeCookie, writeTopicScope } from "./topicScope";

afterEach(() => {
  document.cookie = `${topicScopeCookie(13)}=; path=/; max-age=0`;
  document.cookie = `${topicScopeCookie(8)}=; path=/; max-age=0`;
});

describe("topic scope cookie", () => {
  it("stores all or language per repository", () => {
    writeTopicScope(13, "language");
    writeTopicScope(8, "all");
    expect(readTopicScope(13)).toBe("language");
    expect(readTopicScope(8)).toBe("all");
    expect(document.cookie).toContain(`${topicScopeCookie(13)}=${encodeURIComponent("language")}`);
  });

  it("treats a missing or invalid cookie as unset", () => {
    expect(readTopicScope(13)).toBeNull();
    document.cookie = `${topicScopeCookie(13)}=${encodeURIComponent("nope")}; path=/`;
    expect(readTopicScope(13)).toBeNull();
    document.cookie = `${topicScopeCookie(13)}=${encodeURIComponent('["language"]')}; path=/`;
    expect(readTopicScope(13)).toBeNull();
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
      writeTopicScope(13, "all", "https:");
      expect(assigned.some((value) => value.includes("path=/; max-age=31536000; SameSite=Lax; Secure"))).toBe(true);
      expect(readTopicScope(13)).toBe("all");
    } finally {
      if (descriptor) {
        Object.defineProperty(document, "cookie", descriptor);
      }
    }
  });

  it("prefers the query, then the cookie, and drops language without a repository language", () => {
    expect(resolveVisibleScope("language", "all", true)).toBe("language");
    expect(resolveVisibleScope("language", "language", false)).toBe("all");
    expect(resolveVisibleScope("all", "language", true)).toBe("all");
    expect(resolveVisibleScope("nope", "language", true)).toBe("all");
    expect(resolveVisibleScope(null, "language", true)).toBe("language");
    expect(resolveVisibleScope(null, "language", false)).toBe("all");
    expect(resolveVisibleScope(null, null, true)).toBe("all");
    expect(resolveVisibleScope(null, "all", true)).toBe("all");
  });
});
