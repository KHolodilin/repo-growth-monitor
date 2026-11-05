import { useEffect, useRef, useState } from "react";
import { cn } from "../lib/utils";
import { readCookie, readJsonCookie, writeCookie, writeJsonCookie } from "../lib/cookies";

export const PERIODS = ["1d", "7d", "30d", "90d", "1y", "all"] as const;
export type Period = (typeof PERIODS)[number];

const PERIOD_COOKIE = "rgm-period";
const REPO_PERIODS_COOKIE = "rgm-period-repos";
const DEFAULT_PERIOD: Period = "7d";

function isPeriod(value: string): value is Period {
  return (PERIODS as readonly string[]).includes(value);
}

export function readStoredPeriod(): Period {
  const value = readCookie(PERIOD_COOKIE);
  return value && isPeriod(value) ? value : DEFAULT_PERIOD;
}

export function writeStoredPeriod(period: Period) {
  writeCookie(PERIOD_COOKIE, period);
}

function parseRepoPeriodMap(parsed: unknown): Record<string, Period> | null {
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    return null;
  }
  const result: Record<string, Period> = {};
  for (const [repoId, value] of Object.entries(parsed)) {
    if (typeof value === "string" && isPeriod(value)) {
      result[repoId] = value;
    }
  }
  return result;
}

function readRepoPeriodMap(): Record<string, Period> {
  return readJsonCookie(REPO_PERIODS_COOKIE, parseRepoPeriodMap) ?? {};
}

export function readStoredRepoPeriod(repoId: string): Period {
  return readRepoPeriodMap()[repoId] ?? readStoredPeriod();
}

export function writeStoredRepoPeriod(repoId: string, period: Period) {
  const map = readRepoPeriodMap();
  map[repoId] = period;
  writeJsonCookie(REPO_PERIODS_COOKIE, map);
}

function periodFor(repoId: string | undefined): Period {
  return repoId ? readStoredRepoPeriod(repoId) : readStoredPeriod();
}

export function usePeriod(repoId?: string) {
  const [period, setPeriodState] = useState<Period>(() => periodFor(repoId));
  const [appliedRepoId, setAppliedRepoId] = useState(repoId);

  if (appliedRepoId !== repoId) {
    setAppliedRepoId(repoId);
    setPeriodState(periodFor(repoId));
  }

  function setPeriod(next: Period) {
    if (repoId) {
      writeStoredRepoPeriod(repoId, next);
    } else {
      writeStoredPeriod(next);
    }
    setPeriodState(next);
  }
  return [period, setPeriod] as const;
}

export function PeriodSelector({
  period,
  onPeriod,
  embedded = false,
  scrollable = false,
  fitMobile = false,
}: {
  period: Period;
  onPeriod: (period: Period) => void;
  embedded?: boolean;
  /** Keep chips on one row; parent may provide overflow-x-auto. */
  scrollable?: boolean;
  fitMobile?: boolean;
}) {
  const activeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!scrollable) {
      return;
    }
    activeRef.current?.scrollIntoView({ inline: "nearest", block: "nearest" });
  }, [period, scrollable]);

  const buttons = PERIODS.map((item) => (
    <button
      key={item}
      ref={period === item ? activeRef : undefined}
      type="button"
      className={cn(
        "shrink-0 rounded-md px-3 py-1.5 text-sm font-medium",
        fitMobile && "min-h-11 min-w-0 px-0 md:min-h-0 md:px-3",
        period === item ? "bg-card text-foreground shadow-xs" : "text-muted-foreground hover:text-foreground",
      )}
      aria-pressed={period === item}
      onClick={() => onPeriod(item)}
    >
      {item}
    </button>
  ));
  if (embedded) {
    return buttons;
  }
  return (
    <div
      className={cn(
        "inline-flex max-w-full rounded-lg border bg-muted p-1",
        fitMobile && "grid w-full grid-cols-6 md:inline-flex md:w-auto",
        scrollable && "flex-nowrap overflow-x-auto",
      )}
    >
      {buttons}
    </div>
  );
}
