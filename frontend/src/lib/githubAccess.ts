import { api } from "./api";

export type TokenState =
  | "MISSING"
  | "UNVERIFIED"
  | "ORCHESTRATION_DISABLED"
  | "COLLECTOR_UNAVAILABLE"
  | "OK"
  | "REJECTED"
  | "RATE_LIMITED";

export type PlannerWindow = {
  timezone: string;
  from: string;
  to: string;
  open: boolean;
};

export type GitHubAccess = {
  tokenState: TokenState;
  rateLimitReset: string | null;
  planner: PlannerWindow | null;
};

export const TOKEN_MISSING =
  "No GitHub credential is configured. Add a vault credential for the collector, then refresh.";
export const TOKEN_REJECTED =
  "GitHub rejected the token. Collection is stopped. Stored data is kept. Rotate the vault credential.";
export const ORCHESTRATION_DISABLED =
  "Collection orchestration is disabled. Enable rgm.orchestration and credentials to collect.";
export const COLLECTOR_UNAVAILABLE =
  "Collector is not available. Collection is paused. Stored data is kept.";
export const NO_ACCESS =
  "The token can no longer see this repository. History is kept. Restore it on the token or turn tracking off.";
export const TRAFFIC_PERMISSION = "Missing Administration: Read. Traffic is not collected.";
export const PRIVATE_TRACKING =
  "Public search and topics do not rank this repository. Traffic, stars, and events will still be collected.";

export function parsePlannerWindow(value: unknown): PlannerWindow | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  const record = value as { timezone?: unknown; from?: unknown; to?: unknown; open?: unknown };
  if (typeof record.from !== "string" || record.from.length === 0) {
    return null;
  }
  if (typeof record.to !== "string" || record.to.length === 0) {
    return null;
  }
  if (typeof record.open !== "boolean") {
    return null;
  }
  const timezone = typeof record.timezone === "string" && record.timezone.length > 0 ? record.timezone : "UTC";
  return { timezone, from: record.from, to: record.to, open: record.open };
}

const TOKEN_STATES: readonly TokenState[] = [
  "MISSING",
  "UNVERIFIED",
  "ORCHESTRATION_DISABLED",
  "COLLECTOR_UNAVAILABLE",
  "OK",
  "REJECTED",
  "RATE_LIMITED",
];

function isTokenState(value: unknown): value is TokenState {
  return typeof value === "string" && (TOKEN_STATES as readonly string[]).includes(value);
}

export function parseGitHubAccess(value: unknown): GitHubAccess {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return { tokenState: "OK", rateLimitReset: null, planner: null };
  }
  const record = value as { tokenState?: string; rateLimitReset?: string | null; planner?: unknown };
  const planner = parsePlannerWindow(record.planner);
  if (isTokenState(record.tokenState)) {
    return { tokenState: record.tokenState, rateLimitReset: record.rateLimitReset ?? null, planner };
  }
  return { tokenState: "OK", rateLimitReset: null, planner };
}

export async function loadGitHubAccess(): Promise<GitHubAccess> {
  try {
    return parseGitHubAccess(await api<unknown>("/api/v1/admin/system/status"));
  } catch {
    return { tokenState: "OK", rateLimitReset: null, planner: null };
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
  if (access.tokenState === "ORCHESTRATION_DISABLED") {
    return ORCHESTRATION_DISABLED;
  }
  if (access.tokenState === "COLLECTOR_UNAVAILABLE") {
    return COLLECTOR_UNAVAILABLE;
  }
  if (access.tokenState === "RATE_LIMITED") {
    return rateLimitBanner(access.rateLimitReset);
  }
  // UNVERIFIED: credential exists and a collector node can be used. Only the collector can call
  // GitHub, so there is nothing to warn about until it reports a rejection or a rate limit.
  return null;
}

/** A credential exists and collection is possible; GitHub may not have confirmed it yet. */
export function credentialConfigured(access: GitHubAccess | null): boolean {
  return access?.tokenState === "OK" || access?.tokenState === "RATE_LIMITED" || access?.tokenState === "UNVERIFIED";
}

export function collectionBlocked(access: GitHubAccess | null): boolean {
  return (
    access?.tokenState === "MISSING" ||
    access?.tokenState === "REJECTED" ||
    access?.tokenState === "ORCHESTRATION_DISABLED" ||
    access?.tokenState === "COLLECTOR_UNAVAILABLE"
  );
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
