import { useEffect, useMemo, useState } from "react";
import { api } from "../lib/api";
import { defaultTopSources, readReferrerChartPrefs, writeReferrerChartPrefs, type ReferrerMetric } from "../lib/referrerChartPrefs";
import { cn, formatChartAxisDate, formatNumber, formatSyncTime } from "../lib/utils";
import { PersistentECharts } from "./PersistentECharts";
import { ReferrerSourceIcon, referrerLineColor } from "./ReferrerSourceIcon";
import { Button, Card } from "./ui";

const OTHER = "Other";
const TOP_TABLE = 3;
const DEFAULT_LINES = 4;

export type ReferrerHistory = {
  repositoryId: number;
  period: string;
  from: string;
  to: string;
  snapshotCount: number;
  sources: ReferrerHistorySource[];
  pathSnapshotCount: number;
  paths: { path: string; title?: string; views: number; uniqueVisitors: number }[];
};

export type ReferrerHistorySource = {
  source: string;
  views: number;
  uniqueVisitors: number;
  points: { date: string; views: number | null; visitors: number | null; previousSnapshotDate: string }[];
};

type TableRow = { key: string; title: string; subtitle?: string; views: number; visitors: number; icon?: boolean };

export function ReferrerTrafficPanel({
  repositoryId,
  period,
  referrers,
  paths,
  lastUpdated,
}: {
  repositoryId: number;
  period: string;
  referrers: { referrer: string; views: number; uniqueVisitors: number }[];
  paths: { path: string; title?: string; views: number; uniqueVisitors: number }[];
  lastUpdated?: string | null;
}) {
  const [history, setHistory] = useState<ReferrerHistory | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [metric, setMetric] = useState<ReferrerMetric>("VISITORS");
  const [selected, setSelected] = useState<string[]>([]);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [viewAll, setViewAll] = useState<"referrers" | "paths" | null>(null);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    api<ReferrerHistory>(`/api/v1/repositories/${repositoryId}/referrers/history?period=${period}`)
      .then((data) => {
        if (!cancelled) {
          setHistory(data);
        }
      })
      .catch((err: Error) => {
        if (!cancelled) {
          setError(err.message);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [repositoryId, period]);

  useEffect(() => {
    if (!history) {
      return;
    }
    const stored = readReferrerChartPrefs(repositoryId);
    const available = new Set(history.sources.map((item) => item.source));
    const restored = (stored?.sources ?? []).filter((name) => available.has(name));
    const nextMetric = stored?.metric ?? "VISITORS";
    setMetric(nextMetric);
    setSelected(restored.length > 0 ? restored : defaultTopSources(history.sources, nextMetric, DEFAULT_LINES));
  }, [history, repositoryId]);

  function persist(nextMetric: ReferrerMetric, nextSources: string[]) {
    setMetric(nextMetric);
    setSelected(nextSources);
    writeReferrerChartPrefs(repositoryId, { metric: nextMetric, sources: nextSources });
  }

  const enoughHistory = (history?.snapshotCount ?? 0) >= 2;
  const enoughPathHistory = (history?.pathSnapshotCount ?? 0) >= 2;
  const moreCount = Math.max(0, (history?.sources.length ?? 0) - selected.length);
  const referrerRows = enoughHistory && history
    ? history.sources.map((row) => ({
        key: row.source,
        title: row.source,
        views: row.views,
        visitors: row.uniqueVisitors,
        icon: true,
      }))
    : referrers
        .slice()
        .sort((left, right) => right.uniqueVisitors - left.uniqueVisitors || right.views - left.views)
        .map((row) => ({
          key: row.referrer,
          title: row.referrer,
          views: row.views,
          visitors: row.uniqueVisitors,
          icon: true,
        }));
  const pathRows = enoughPathHistory && history
    ? history.paths.map((row) => ({
        key: row.path,
        title: row.path,
        subtitle: row.title,
        views: row.views,
        visitors: row.uniqueVisitors,
      }))
    : paths.map((row) => ({
        key: row.path,
        title: row.path,
        subtitle: row.title,
        views: row.views,
        visitors: row.uniqueVisitors,
      }));

  return (
    <div className="space-y-4">
      <Card>
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
          <h2 className="font-medium">Referrer Traffic (by day)</h2>
          <div className="inline-flex rounded-lg border bg-muted p-1">
            <MetricButton active={metric === "VISITORS"} onClick={() => persist("VISITORS", selected)}>
              Visitors
            </MetricButton>
            <MetricButton active={metric === "VIEWS"} onClick={() => persist("VIEWS", selected)}>
              Views
            </MetricButton>
          </div>
        </div>
        {error && <div className="text-sm text-red-700">{error}</div>}
        {!error && !enoughHistory && (
          <div className="rounded-lg border border-dashed px-4 py-10 text-center text-sm text-muted-foreground">
            <div className="font-medium text-foreground">Not enough history yet.</div>
            <div className="mt-1">At least two referrer snapshots are required.</div>
          </div>
        )}
        {!error && enoughHistory && history && (
          <ReferrerChart history={history} metric={metric} selected={selected} />
        )}
        {!error && enoughHistory && history && (
          <div className="relative mt-3">
            <button
              type="button"
              className="text-sm font-medium text-primary"
              onClick={() => setPickerOpen((open) => !open)}
            >
              + {moreCount} more sources
            </button>
            {pickerOpen && (
              <div className="absolute z-20 mt-2 w-72 rounded-lg border bg-card p-3 shadow-lg">
                <div className="mb-2 text-sm font-medium">Select sources</div>
                <div className="max-h-64 space-y-1 overflow-y-auto">
                  {history.sources.map((item) => (
                    <label key={item.source} className="flex cursor-pointer items-center gap-2 rounded-md px-1 py-1 text-sm hover:bg-muted">
                      <input
                        type="checkbox"
                        checked={selected.includes(item.source)}
                        onChange={() => {
                          const next = selected.includes(item.source)
                            ? selected.filter((name) => name !== item.source)
                            : [...selected, item.source];
                          persist(metric, next);
                        }}
                      />
                      <ReferrerSourceIcon source={item.source} />
                      <span>{item.source}</span>
                    </label>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </Card>
      <div className="grid gap-4 md:grid-cols-2">
        <CompactTable
          title="Top Referrers"
          rows={referrerRows}
          onViewAll={() => setViewAll("referrers")}
        />
        <CompactTable
          title="Popular Paths"
          firstColumn="Path"
          rows={pathRows}
          onViewAll={() => setViewAll("paths")}
        />
      </div>
      <div className="text-xs text-muted-foreground">
        <div>
          <span className="font-medium text-foreground">About data.</span> Data is collected once per day. Referrer
          traffic shows daily delta between snapshots.
        </div>
        {lastUpdated && <div className="mt-1">Last updated: {formatSyncTime(lastUpdated)}</div>}
      </div>
      {viewAll && (
        <ViewAllDialog
          title={viewAll === "referrers" ? "All Referrers" : "All Popular Paths"}
          firstColumn={viewAll === "referrers" ? "Source" : "Path"}
          rows={viewAll === "referrers" ? referrerRows : pathRows}
          onClose={() => setViewAll(null)}
        />
      )}
    </div>
  );
}

function ReferrerChart({
  history,
  metric,
  selected,
}: {
  history: ReferrerHistory;
  metric: ReferrerMetric;
  selected: string[];
}) {
  const visitors = metric === "VISITORS";
  const option = useMemo(() => {
    const dates = Array.from(
      new Set(history.sources.flatMap((source) => source.points.map((point) => point.date))),
    ).sort();
    const bySource = new Map(history.sources.map((source) => [source.source, source]));
    const seriesNames = [...selected, OTHER];
    const previousByDate = new Map<string, string>();
    for (const source of history.sources) {
      for (const point of source.points) {
        if (!previousByDate.has(point.date) || point.previousSnapshotDate < (previousByDate.get(point.date) ?? "")) {
          previousByDate.set(point.date, point.previousSnapshotDate);
        }
      }
    }
    return {
      tooltip: {
        trigger: "axis" as const,
        formatter: (params: { axisValue: string; seriesName: string; data: number | null }[]) => {
          if (!Array.isArray(params) || params.length === 0) {
            return "";
          }
          const date = params[0].axisValue;
          const previous = previousByDate.get(date);
          const gap =
            previous && nextDay(previous) !== date
              ? `<div style="margin-bottom:6px;opacity:.7">Since previous snapshot: ${formatChartAxisDate(previous)}</div>`
              : "";
          const unit = visitors ? "Visitors" : "Views";
          const rows = params
            .map((item) => {
              const value = item.data === null || item.data === undefined ? "—" : formatNumber(item.data);
              return `<div style="display:flex;justify-content:space-between;gap:24px"><span>${item.seriesName}</span><span>${value} ${unit}</span></div>`;
            })
            .join("");
          return `<div style="min-width:180px"><div style="margin-bottom:6px">${formatChartAxisDate(date)}</div>${gap}${rows}</div>`;
        },
      },
      grid: { left: 48, right: 72, top: 16, bottom: 40, containLabel: false },
      xAxis: {
        type: "category",
        data: dates,
        boundaryGap: dates.length < 2,
        axisLabel: {
          hideOverlap: true,
          showMinLabel: true,
          showMaxLabel: true,
          alignMinLabel: "left",
          alignMaxLabel: "right",
          formatter: (value: string) => formatChartAxisDate(String(value)),
        },
      },
      yAxis: { type: "value", name: visitors ? "Visitors" : "Views" },
      series: seriesNames.map((name, index) => ({
        name,
        type: "line",
        showSymbol: true,
        symbolSize: 8,
        connectNulls: false,
        itemStyle: { color: referrerLineColor(name, index) },
        lineStyle: { color: referrerLineColor(name, index) },
        data: dates.map((date) => {
          if (name === OTHER) {
            let sum: number | null = null;
            for (const source of history.sources) {
              if (selected.includes(source.source)) {
                continue;
              }
              const point = source.points.find((item) => item.date === date);
              const value = point ? (visitors ? point.visitors : point.views) : null;
              if (value != null) {
                sum = (sum ?? 0) + value;
              }
            }
            return sum;
          }
          const point = bySource.get(name)?.points.find((item) => item.date === date);
          return point ? (visitors ? point.visitors : point.views) : null;
        }),
      })),
    };
  }, [history, metric, selected, visitors]);

  const legend = [...selected, OTHER];

  return (
    <div>
      <div className="mb-3 flex flex-wrap gap-3 text-sm">
        {legend.map((name, index) => (
          <span key={name} className="inline-flex items-center gap-1.5">
            <span className="h-2.5 w-2.5 rounded-full" style={{ background: referrerLineColor(name, index) }} />
            <ReferrerSourceIcon source={name} />
            {name}
          </span>
        ))}
      </div>
      <PersistentECharts
        chartId={`referrer-unused-${history.repositoryId}`}
        series={legend.map((name) => ({ key: name, name }))}
        option={option}
        style={{ height: 320, width: "100%" }}
      />
    </div>
  );
}

function CompactTable({
  title,
  rows,
  onViewAll,
  firstColumn = "Source",
}: {
  title: string;
  rows: TableRow[];
  onViewAll: () => void;
  firstColumn?: string;
}) {
  return (
    <Card>
      <div className="mb-3 flex items-center justify-between gap-3">
        <h2 className="font-medium">{title}</h2>
        {rows.length > 0 && (
          <button type="button" className="text-sm font-medium text-primary" onClick={onViewAll}>
            View all →
          </button>
        )}
      </div>
      <MetricRows rows={rows.slice(0, TOP_TABLE)} firstColumn={firstColumn} />
    </Card>
  );
}

function ViewAllDialog({
  title,
  rows,
  onClose,
  firstColumn = "Source",
}: {
  title: string;
  rows: TableRow[];
  onClose: () => void;
  firstColumn?: string;
}) {
  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <Card className="max-h-[80vh] w-full max-w-xl overflow-hidden" onClick={(event) => event.stopPropagation()}>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="font-medium">{title}</h2>
          <Button type="button" className="bg-muted text-foreground" onClick={onClose}>
            Close
          </Button>
        </div>
        <div className="max-h-[60vh] overflow-auto">
          <MetricRows rows={rows} firstColumn={firstColumn} />
        </div>
      </Card>
    </div>
  );
}

function MetricRows({ rows, firstColumn = "Source" }: { rows: TableRow[]; firstColumn?: string }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full table-fixed text-sm">
        <thead>
          <tr className="text-left text-muted-foreground">
            <th className="pr-3">{firstColumn}</th>
            <th className="w-[4.75rem] whitespace-nowrap pl-2 text-right">Visitors</th>
            <th className="w-16 whitespace-nowrap pl-2 text-right">Views</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.key} className="border-t">
              <td className="max-w-0 py-2 pr-3 align-top">
                <div className="flex items-start gap-2">
                  {row.icon && <ReferrerSourceIcon source={row.title} />}
                  <div>
                    <div className="[overflow-wrap:anywhere]">{wrapPath(row.title)}</div>
                    {row.subtitle && <div className="text-xs text-muted-foreground">{row.subtitle}</div>}
                  </div>
                </div>
              </td>
              <td className="whitespace-nowrap pl-2 text-right align-top">{formatNumber(row.visitors)}</td>
              <td className="whitespace-nowrap pl-2 text-right align-top">{formatNumber(row.views)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function MetricButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: string;
}) {
  return (
    <button
      type="button"
      className={cn(
        "rounded-md px-3 py-1.5 text-sm font-medium",
        active ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground",
      )}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

function wrapPath(value: string) {
  return value.replaceAll("/", "/\u200b");
}

function nextDay(value: string) {
  const date = new Date(`${value}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}
