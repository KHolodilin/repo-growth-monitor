import { api } from "./api";
import { readCookie } from "./cookies";

export type Me = {
  authenticated: boolean;
  userId: string | null;
  displayName: string | null;
  roles: string[];
  actions: string[];
  loginUrl: string;
  logoutUrl: string;
};

export const anonymousMe: Me = {
  authenticated: false,
  userId: null,
  displayName: null,
  roles: [],
  actions: [],
  loginUrl: "/login",
  logoutUrl: "/logout",
};

export function canMutate(me: Me | null | undefined): boolean {
  return Boolean(me?.authenticated && me.actions.includes("MUTATE"));
}

export function mutationBlockedReason(me: Me | null | undefined): string | null {
  if (me == null) {
    return "Checking access…";
  }
  if (!me.authenticated) {
    return "Sign in as an admin to change data.";
  }
  if (!canMutate(me)) {
    return "Admin role required to change data.";
  }
  return null;
}

export async function loadMe(): Promise<Me> {
  return api<Me>("/api/v1/me");
}

/** Logout may redirect to HTML; never parse the body as JSON. */
export async function signOut(logoutUrl: string): Promise<void> {
  const headers = new Headers();
  const csrf = readCookie("XSRF-TOKEN");
  if (csrf) {
    headers.set("X-XSRF-TOKEN", csrf);
  }
  await fetch(logoutUrl || "/logout", {
    method: "POST",
    headers,
    credentials: "include",
  });
}
