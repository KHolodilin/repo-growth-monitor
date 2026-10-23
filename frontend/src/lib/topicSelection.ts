const MAX_AGE = 60 * 60 * 24 * 365;
const DEFAULT_COUNT = 5;

export function topicSelectionCookie(repositoryId: number) {
  return `topics_visibility_topics_${repositoryId}`;
}

export function readTopicSelection(repositoryId: number): string[] | null {
  const name = topicSelectionCookie(repositoryId);
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  if (!match) {
    return null;
  }
  try {
    const parsed = JSON.parse(decodeURIComponent(match[1])) as unknown;
    if (!Array.isArray(parsed) || parsed.some((item) => typeof item !== "string")) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

export function writeTopicSelection(repositoryId: number, slugs: string[], protocol = currentProtocol()) {
  const secure = protocol === "https:" ? "; Secure" : "";
  const value = encodeURIComponent(JSON.stringify(slugs));
  document.cookie = `${topicSelectionCookie(repositoryId)}=${value}; path=/; max-age=${MAX_AGE}; SameSite=Lax${secure}`;
}

function currentProtocol() {
  return location.protocol;
}

export function defaultTopicSlugs(topics: { slug: string; rank: number | null }[]) {
  return [...topics]
    .sort((left, right) => {
      if (left.rank == null && right.rank == null) {
        return left.slug.localeCompare(right.slug);
      }
      if (left.rank == null) {
        return 1;
      }
      if (right.rank == null) {
        return -1;
      }
      if (left.rank !== right.rank) {
        return left.rank - right.rank;
      }
      return left.slug.localeCompare(right.slug);
    })
    .slice(0, DEFAULT_COUNT)
    .map((topic) => topic.slug);
}

/** A saved cookie, including an empty one, wins. A missing cookie selects the five best ranks. */
export function resolveTopicSelection(
  repositoryId: number,
  topics: { slug: string; rank: number | null }[],
) {
  const known = new Set(topics.map((topic) => topic.slug));
  const stored = readTopicSelection(repositoryId);
  if (stored) {
    const next = stored.filter((slug) => known.has(slug));
    if (next.length !== stored.length) {
      writeTopicSelection(repositoryId, next);
    }
    return next;
  }
  const next = defaultTopicSlugs(topics);
  writeTopicSelection(repositoryId, next);
  return next;
}
