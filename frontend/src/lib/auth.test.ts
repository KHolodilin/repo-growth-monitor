import { describe, expect, it } from "vitest";
import { anonymousMe, canMutate, mutationBlockedReason, type Me } from "./auth";

describe("auth helpers", () => {
  it("detects mutate permission and blocked reasons", () => {
    expect(canMutate(anonymousMe)).toBe(false);
    expect(mutationBlockedReason(null)).toContain("Checking");
    expect(mutationBlockedReason(anonymousMe)).toContain("Sign in");

    const user: Me = {
      ...anonymousMe,
      authenticated: true,
      userId: "1",
      displayName: "User",
      roles: ["USER"],
      actions: [],
    };
    expect(mutationBlockedReason(user)).toContain("Admin role");

    const admin: Me = { ...user, roles: ["USER", "ADMIN"], actions: ["MUTATE"] };
    expect(canMutate(admin)).toBe(true);
    expect(mutationBlockedReason(admin)).toBeNull();
  });
});
