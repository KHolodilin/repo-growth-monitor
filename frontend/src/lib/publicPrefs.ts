const KEY = "rgm-public-preferences-v1";
const LIMIT = 256;
let memory: Record<string, unknown> = {};
function readAll(): Record<string, unknown> { try { const value = JSON.parse(localStorage.getItem(KEY) ?? "{}"); if (value && typeof value === "object" && !Array.isArray(value)) memory = Object.fromEntries(Object.entries(value).slice(-LIMIT)); } catch { /* Keep bounded memory when storage is unavailable. */ } return memory; }
export function readPublicPreference<T>(scope: string, key: string, valid: (value: unknown) => value is T): T | undefined { const value = readAll()[`${scope}:${key}`]; return valid(value) ? value : undefined; }
export function writePublicPreference(scope: string, key: string, value: unknown) { const map = { ...readAll() }; const name = `${scope}:${key}`; delete map[name]; if ((JSON.stringify(value)?.length ?? Infinity) <= 16384) map[name] = value; while (Object.keys(map).length > LIMIT) delete map[Object.keys(map)[0]]; memory = map; try { localStorage.setItem(KEY, JSON.stringify(map)); } catch { /* Preference failure cannot break navigation or charts. */ } }
export function publicContext(value: string | undefined) { return Boolean(value?.startsWith("public:")); }
