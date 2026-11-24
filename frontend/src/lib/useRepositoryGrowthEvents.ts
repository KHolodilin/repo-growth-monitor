import { useCallback, useEffect, useRef, useState } from "react";
import { api, type GrowthEvent } from "./api";
import { asGrowthEvents } from "./growthEvents";

export function fetchRepositoryGrowthEvents(
  repositoryId: number,
  period: string,
  signal: AbortSignal,
): Promise<GrowthEvent[]> {
  return api<GrowthEvent[]>(
    `/api/v1/admin/repositories/${repositoryId}/growth-events?period=${period}`,
    { signal },
  ).then(asGrowthEvents);
}

function bindGrowthEventsRequest(
  request: Promise<GrowthEvent[]>,
  options: {
    version: number;
    getVersion: () => number;
    signal: AbortSignal;
    onSuccess: (events: GrowthEvent[]) => void;
    onError: (message: string) => void;
    onSettled: () => void;
  },
) {
  return request
    .then((rows) => {
      if (options.version !== options.getVersion() || options.signal.aborted) return;
      options.onSuccess(rows);
    })
    .catch((err: unknown) => {
      if (options.version !== options.getVersion() || options.signal.aborted) return;
      options.onError(err instanceof Error ? err.message : String(err));
    })
    .finally(() => {
      if (options.version !== options.getVersion() || options.signal.aborted) return;
      options.onSettled();
    });
}

/**
 * Loads repository growth events with generation + abort guards so period/repo
 * changes and overlapping onChanged reloads cannot apply stale success/error.
 */
export function useRepositoryGrowthEvents(repositoryId: number, period: string) {
  const loadKey = `${repositoryId}:${period}`;
  const [seenKey, setSeenKey] = useState(loadKey);
  const [events, setEvents] = useState<GrowthEvent[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const requestVersion = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  if (loadKey !== seenKey) {
    setSeenKey(loadKey);
    setEvents([]);
    setError(null);
    setLoading(true);
  }

  const begin = useCallback(() => {
    const version = ++requestVersion.current;
    abortRef.current?.abort();
    const abort = new AbortController();
    abortRef.current = abort;
    return { version, abort };
  }, []);

  const follow = useCallback(
    (version: number, abort: AbortController) =>
      bindGrowthEventsRequest(fetchRepositoryGrowthEvents(repositoryId, period, abort.signal), {
        version,
        getVersion: () => requestVersion.current,
        signal: abort.signal,
        onSuccess: (rows) => {
          setEvents(rows);
          setError(null);
        },
        onError: (message) => setError(message),
        onSettled: () => setLoading(false),
      }),
    [repositoryId, period],
  );

  useEffect(() => {
    const { version, abort } = begin();
    void follow(version, abort);
    return () => {
      requestVersion.current += 1;
      // Cancel whichever request is current, including one started by reload().
      abortRef.current?.abort();
    };
  }, [begin, follow]);

  const reload = useCallback(() => {
    const { version, abort } = begin();
    setLoading(true);
    void follow(version, abort);
  }, [begin, follow]);

  return { events, error, loading, reload };
}
