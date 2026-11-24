/** Canonical in-app repository URLs: /{owner}/{repo} only. */

export function repoPath(owner: string, repository: string, suffix = "") {
  const base = `/${encodeURIComponent(owner)}/${encodeURIComponent(repository)}`;
  return suffix ? `${base}${suffix.startsWith("/") ? suffix : `/${suffix}`}` : base;
}

export function repoPathFromFullName(fullName: string, suffix = "") {
  const [owner, ...rest] = fullName.split("/");
  const repository = rest.join("/");
  if (!owner || !repository) {
    return suffix || "/";
  }
  return repoPath(owner, repository, suffix);
}

export function ownerPath(owner: string) {
  return `/${encodeURIComponent(owner)}`;
}

export function repoTabQuery(tab: string | undefined) {
  if (!tab || tab === "traffic") {
    return "";
  }
  return `?tab=${encodeURIComponent(tab)}`;
}

export function repoHref(owner: string, repository: string, tab?: string) {
  return `${repoPath(owner, repository)}${repoTabQuery(tab)}`;
}

export function repoHrefFromFullName(fullName: string, tab?: string) {
  return `${repoPathFromFullName(fullName)}${repoTabQuery(tab)}`;
}
