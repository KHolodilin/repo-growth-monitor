const MAX_AGE = 60 * 60 * 24 * 365;

/** Cookie names carry dots, which must not act as regular expression wildcards. */
function escapeName(name: string) {
  return name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${escapeName(name)}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

export function writeCookie(name: string, value: string) {
  document.cookie = `${name}=${encodeURIComponent(value)}; path=/; max-age=${MAX_AGE}; SameSite=Lax`;
}

export function readJsonCookie<T>(name: string, parse: (value: unknown) => T | null): T | null {
  const raw = readCookie(name);
  if (!raw) {
    return null;
  }
  try {
    return parse(JSON.parse(raw));
  } catch {
    return null;
  }
}

export function writeJsonCookie(name: string, value: unknown) {
  writeCookie(name, JSON.stringify(value));
}
