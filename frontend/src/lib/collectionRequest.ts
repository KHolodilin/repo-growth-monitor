import { api } from "./api";

export type CollectionRequestState = {
  state: string;
};

/** Poll until the collection request leaves ACTIVE (SUCCEEDED / FAILED / CANCELLED). */
export async function waitForCollectionRequest(
  requestId: string,
  options?: { signal?: AbortSignal; intervalMs?: number },
): Promise<CollectionRequestState> {
  const intervalMs = options?.intervalMs ?? 1000;
  for (;;) {
    const state = await api<CollectionRequestState>(`/api/v1/admin/collection-requests/${requestId}`, {
      signal: options?.signal,
    });
    if (state.state !== "ACTIVE") {
      return state;
    }
    await new Promise<void>((resolve, reject) => {
      const timer = window.setTimeout(resolve, intervalMs);
      options?.signal?.addEventListener(
        "abort",
        () => {
          window.clearTimeout(timer);
          reject(options.signal!.reason ?? new DOMException("Aborted", "AbortError"));
        },
        { once: true },
      );
    });
  }
}
