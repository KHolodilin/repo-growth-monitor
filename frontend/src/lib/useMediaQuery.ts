import { useEffect, useState } from "react";

/** True when the viewport matches `query`. SSR / first paint uses `false`. */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(false);

  useEffect(() => {
    if (typeof window.matchMedia !== "function") {
      return;
    }
    const media = window.matchMedia(query);
    const sync = () => setMatches(media.matches);
    sync();
    media.addEventListener("change", sync);
    return () => media.removeEventListener("change", sync);
  }, [query]);

  return matches;
}

/** Tailwind `md` is 768px — mobile ChartCard shell below that. */
export function useIsMobileChart(): boolean {
  return useMediaQuery("(max-width: 767px)");
}
