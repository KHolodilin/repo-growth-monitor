import { cn } from "../lib/utils";

export const PERIODS = ["7d", "30d", "90d", "1y", "all"] as const;
export type Period = (typeof PERIODS)[number];

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
