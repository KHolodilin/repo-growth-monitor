import { useState } from "react";
import { readJsonCookie, writeJsonCookie } from "./cookies";

const COOKIE = "repo-growth.service-paths";

/** One cookie holds every repository, the same way the table sort cookie does. */
function parseScopes(value: unknown): Record<string, boolean> | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  const scopes: Record<string, boolean> = {};
  for (const [scope, shown] of Object.entries(value)) {
    if (typeof shown === "boolean") {
      scopes[scope] = shown;
    }
  }
  return scopes;
}

function readScopes(): Record<string, boolean> {
  return readJsonCookie(COOKIE, parseScopes) ?? {};
}

/**
 * Repository tabs such as Insights or Issues are mostly the owner looking at their own project,
 * so they stay hidden until asked for.
 */
export function useServicePaths(repositoryId: number | string | undefined) {
  const scope = String(repositoryId ?? "global");
  const [shown, setShown] = useState(() => readScopes()[scope] ?? false);
  const [appliedScope, setAppliedScope] = useState(scope);

  if (appliedScope !== scope) {
    setAppliedScope(scope);
    setShown(readScopes()[scope] ?? false);
  }

  function toggle(next: boolean) {
    setShown(next);
    const scopes = readScopes();
    scopes[scope] = next;
    writeJsonCookie(COOKIE, scopes);
  }

  return { showServicePaths: shown, setShowServicePaths: toggle };
}
