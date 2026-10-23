import type { Period } from "./PeriodSelector";
import { Card } from "./ui";
import { cn, formatAbsoluteGrowth, formatGrowth, formatNumber, growthClass } from "../lib/utils";

export function TrafficKpiCard({
  label,
  value,
  growthPercent,
  absoluteChange,
  period,
  series,
  sparklineColor,
  note,
  className,
  preserveGaps = false,
  missingHistory = false,
}: {
  label: string;
  value: number | null;
  growthPercent: number | null;
  absoluteChange?: number | null;
  period: Period;
  series?: (number | null | undefined)[];
  sparklineColor?: string;
  note?: string;
  className?: string;
  /** Keep nulls as breaks instead of connecting the measured days. */
  preserveGaps?: boolean;
  missingHistory?: boolean;
}) {
  const growth =
    period === "all"
      ? null
      : absoluteChange === undefined
        ? formatGrowth(growthPercent)
        : formatAbsoluteGrowth(absoluteChange, growthPercent);
  const sparklineValues = preserveGaps ? (series ?? []) : (series ?? []).filter((point): point is number => point != null);
  const hasSparkline = sparklineValues.some((point) => point != null);
  return (
    <Card className={cn("flex h-full flex-col p-3", className)}>
      <div className="text-sm text-muted-foreground">
        {label}
        {note && <div className="text-xs">{note}</div>}
      </div>
      <div className="mt-1 text-2xl font-semibold tabular-nums">{missingHistory && value == null ? "N/A" : formatNumber(value)}</div>
      {period !== "all" && (
        <div className="mt-1 flex items-end justify-between gap-2">
          <div className={cn("text-sm font-medium", growth ? growthClass(growth.direction) : "text-muted-foreground")}>
            {missingHistory && !growth ? "N/A" : (growth?.label ?? "—")}
            {growth && <div className="text-xs font-normal text-muted-foreground">vs prev {period}</div>}
          </div>
          {hasSparkline && (
            <Sparkline values={sparklineValues} direction={growth?.direction} color={sparklineColor} />
          )}
        </div>
      )}
    </Card>
  );
}

function Sparkline({
  values,
  direction,
  color,
}: {
  values: (number | null | undefined)[];
  direction?: "up" | "down" | "flat";
  color?: string;
}) {
  const width = 72;
  const height = 24;
  const numeric = values.filter((value): value is number => value != null);
  const min = Math.min(...numeric);
  const max = Math.max(...numeric);
  const span = max - min || 1;
  const segments: string[] = [];
  let current: string[] = [];
  values.forEach((value, index) => {
    if (value == null) {
      if (current.length > 0) {
        segments.push(current.join(" "));
        current = [];
      }
      return;
    }
    const x = values.length === 1 ? width / 2 : (index / (values.length - 1)) * width;
    const y = height - ((value - min) / span) * (height - 2) - 1;
    current.push(`${x},${y}`);
  });
  if (current.length > 0) {
    segments.push(current.join(" "));
  }
  const stroke = color ?? (direction === "down" ? "#b91c1c" : direction === "flat" || direction == null ? "#6b7280" : "#047857");
  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="h-6 w-16 shrink-0" aria-hidden="true">
      {segments.map((points) => (
        <polyline key={points} fill="none" stroke={stroke} strokeWidth="1.5" points={points} />
      ))}
    </svg>
  );
}
