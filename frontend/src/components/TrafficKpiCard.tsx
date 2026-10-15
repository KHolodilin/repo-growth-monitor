import type { Period } from "./PeriodSelector";
import { Card } from "./ui";
import { cn, formatGrowth, formatNumber, growthClass } from "../lib/utils";

export function TrafficKpiCard({
  label,
  value,
  growthPercent,
  period,
  series,
}: {
  label: string;
  value: number;
  growthPercent: number | null;
  period: Period;
  series?: (number | null | undefined)[];
}) {
  const growth = period === "all" ? null : formatGrowth(growthPercent);
  const points = (series ?? []).filter((point): point is number => point != null);
  return (
    <Card className="p-3">
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className="mt-1 text-2xl font-semibold tabular-nums">{formatNumber(value)}</div>
      {period !== "all" && (
        <div className="mt-1 flex items-end justify-between gap-2">
          <div className={cn("text-sm font-medium", growth ? growthClass(growth.direction) : "text-muted-foreground")}>
            {growth?.label ?? "—"}
            {growth && <div className="text-xs font-normal text-muted-foreground">vs prev {period}</div>}
          </div>
          {points.length > 0 && <Sparkline values={points} direction={growth?.direction} />}
        </div>
      )}
    </Card>
  );
}

function Sparkline({ values, direction }: { values: number[]; direction?: "up" | "down" | "flat" }) {
  const width = 72;
  const height = 24;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const points = values
    .map((value, index) => {
      const x = values.length === 1 ? width / 2 : (index / (values.length - 1)) * width;
      const y = height - ((value - min) / span) * (height - 2) - 1;
      return `${x},${y}`;
    })
    .join(" ");
  const stroke = direction === "down" ? "#b91c1c" : direction === "flat" || direction == null ? "#6b7280" : "#047857";
  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="h-6 w-16 shrink-0" aria-hidden="true">
      <polyline fill="none" stroke={stroke} strokeWidth="1.5" points={points} />
    </svg>
  );
}
