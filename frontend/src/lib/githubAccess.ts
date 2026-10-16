import { api } from "./api";

export type TokenState = "MISSING" | "OK" | "REJECTED" | "RATE_LIMITED";

export type GitHubAccess = {
  tokenState: TokenState;
  rateLimitReset: string | null;
};

export const TOKEN_MISSING = "Token is not configured. Set GITHUB_TOKEN in .env and restart.";
export const TOKEN_REJECTED =
  "GitHub rejected the token. Collection is stopped. Stored data is kept. Update GITHUB_TOKEN and restart.";
export const NO_ACCESS =
  "The token can no longer see this repository. History is kept. Restore it on the token or turn tracking off.";
export const TRAFFIC_PERMISSION = "Missing Administration: Read. Traffic is not collected.";
export const PRIVATE_TRACKING =
  "Public search and topics do not rank this repository. Traffic, stars, and events will still be collected.";

export function parseGitHubAccess(value: unknown): GitHubAccess {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return { tokenState: "OK", rateLimitReset: null };
  }
  const record = value as { tokenState?: string; rateLimitReset?: string | null };
  if (record.tokenState === "MISSING" || record.tokenState === "REJECTED" || record.tokenState === "RATE_LIMITED" || record.tokenState === "OK") {
    return { tokenState: record.tokenState, rateLimitReset: record.rateLimitReset ?? null };
  }
  return { tokenState: "OK", rateLimitReset: null };
}

export async function loadGitHubAccess(): Promise<GitHubAccess> {
  try {
    return parseGitHubAccess(await api<unknown>("/api/v1/system/status"));
  } catch {
    return { tokenState: "OK", rateLimitReset: null };
  }
}

export function tokenBanner(access: GitHubAccess | null): string | null {
  if (!access) {
    return null;
  }
  if (access.tokenState === "MISSING") {
    return TOKEN_MISSING;
  }
  if (access.tokenState === "REJECTED") {
    return TOKEN_REJECTED;
  }
  if (access.tokenState === "RATE_LIMITED") {
    return rateLimitBanner(access.rateLimitReset);
  }
  return null;
}

export function collectionBlocked(access: GitHubAccess | null): boolean {
  return access?.tokenState === "MISSING" || access?.tokenState === "REJECTED";
}

function rateLimitBanner(reset: string | null): string {
  if (!reset) {
    return "Collection continues after the rate limit resets.";
  }
  const date = new Date(reset);
  if (Number.isNaN(date.getTime())) {
    return "Collection continues after the rate limit resets.";
  }
  return `Collection continues after ${date.toLocaleString()}.`;
}
