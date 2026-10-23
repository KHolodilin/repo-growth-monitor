import { readCookie } from "./cookies";

const MAX_AGE = 60 * 60 * 24 * 365;

export type TopicScope = "all" | "language";

export function topicScopeCookie(repositoryId: number) {
  return `topics_visibility_scope_${repositoryId}`;
}

export function readTopicScope(repositoryId: number): TopicScope | null {
  const value = readCookie(topicScopeCookie(repositoryId));
  if (value === "all" || value === "language") {
    return value;
  }
  return null;
}

export function writeTopicScope(repositoryId: number, scope: TopicScope, protocol = location.protocol) {
  const secure = protocol === "https:" ? "; Secure" : "";
  document.cookie = `${topicScopeCookie(repositoryId)}=${encodeURIComponent(scope)}; path=/; max-age=${MAX_AGE}; SameSite=Lax${secure}`;
}

/** The topics tab shows the query when it is present, otherwise the saved choice. */
export function resolveVisibleScope(query: string | null, stored: TopicScope | null, hasLanguage: boolean): TopicScope {
  if (query === "language") {
    return hasLanguage ? "language" : "all";
  }
  if (query != null) {
    return "all";
  }
  if (stored === "language" && hasLanguage) {
    return "language";
  }
  return "all";
}
