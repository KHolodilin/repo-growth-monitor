import { useCallback, useSyncExternalStore } from "react";
const values = new Map<string, Record<string, boolean>>();
const listeners = new Map<string, Set<() => void>>();
export function readChartVisibility(id: string) {
  return values.get(id) ?? null;
}
export function publishChartVisibility(id: string, flags: Record<string, boolean>) {
  values.set(id, flags);
  listeners.get(id)?.forEach((notify) => notify());
}
export function subscribeChartVisibility(id: string, notify: () => void) {
  const set = listeners.get(id) ?? new Set<() => void>();
  listeners.set(id, set);
  set.add(notify);
  return () => {
    set.delete(notify);
    if (!set.size) {
      listeners.delete(id);
      values.delete(id);
    }
  };
}
/** Clears in-memory visibility between Vitest cases; production never calls this. */
export function resetChartVisibilityStore() {
  values.clear();
  listeners.clear();
}
/** Candidate membership and temporary line visibility are distinct, but their controls agree. */
export function useChartVisibility(id: string) {
  const subscribe = useCallback((notify: () => void) => subscribeChartVisibility(id, notify), [id]);
  const flags = useSyncExternalStore(
    subscribe,
    () => readChartVisibility(id),
    () => null,
  );
  return {
    isVisible: (key: string) => flags?.[key] !== false,
    reveal: (keys: string[]) =>
      publishChartVisibility(id, {
        ...readChartVisibility(id),
        ...Object.fromEntries(keys.map((key) => [key, true])),
      }),
  };
}
