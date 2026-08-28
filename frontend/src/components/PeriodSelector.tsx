import { useState } from "react";
import { cn } from "../lib/utils";

export const PERIODS = ["1d", "7d", "30d", "90d", "1y", "all"] as const;
export type Period = (typeof PERIODS)[number];

const PERIOD_COOKIE = "rgm-period";
const REPO_PERIODS_COOKIE = "rgm-period-repos";
const DEFAULT_PERIOD: Period = "30d";
const PERIOD_COOKIE_MAX_AGE = 60 * 60 * 24 * 365;

function cookieAttributes() {
  return `path=/; max-age=${PERIOD_COOKIE_MAX_AGE}; SameSite=Lax`;
}

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

function writeCookie(name: string, value: string) {
  document.cookie = `${name}=${encodeURIComponent(value)}; ${cookieAttributes()}`;
}

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

function readRepoPeriodMap(): Record<string, Period> {
  const raw = readCookie(REPO_PERIODS_COOKIE);
  if (!raw) {
    return {};
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return {};
    }
    const result: Record<string, Period> = {};
    for (const [repoId, value] of Object.entries(parsed)) {
      if (typeof value === "string" && isPeriod(value)) {
        result[repoId] = value;
      }
    }
    return result;
  } catch {
    return {};
  }
}

export function readStoredRepoPeriod(repoId: string): Period {
  return readRepoPeriodMap()[repoId] ?? readStoredPeriod();
}

export function writeStoredRepoPeriod(repoId: string, period: Period) {
  const map = readRepoPeriodMap();
  map[repoId] = period;
  writeCookie(REPO_PERIODS_COOKIE, JSON.stringify(map));
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

export function PeriodSelector({ period, onPeriod }: { period: Period; onPeriod: (period: Period) => void }) {
  return (
    <div className="inline-flex rounded-lg border bg-muted p-1">
      {PERIODS.map((item) => (
        <button
          key={item}
          type="button"
          className={cn(
            "rounded-md px-3 py-1.5 text-sm font-medium",
            period === item ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground",
          )}
          onClick={() => onPeriod(item)}
        >
          {item}
        </button>
      ))}
    </div>
  );
}
