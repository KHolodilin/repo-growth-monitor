import { useEffect, useMemo, useState } from "react";
import type { SnapshotHistory } from "../lib/api";
import { snapshotChartOption } from "../lib/snapshotChart";
import {
  readSnapshotChartPrefs,
  writeSnapshotChartPrefs,
  type SnapshotChartKind,
  type SnapshotMetric,
} from "../lib/snapshotChartPrefs";
import { OTHER_KEY, defaultTopKeys } from "../lib/snapshotHistory";
import { cn } from "../lib/utils";
import { PersistentECharts } from "./PersistentECharts";
import { ReferrerSourceIcon, referrerLineColor } from "./ReferrerSourceIcon";
import { Card } from "./ui";

const DEFAULT_LINES = 5;

export function SnapshotHistoryChart({
  title,
  kind,
  history,
}: {
  title: string;
  kind: SnapshotChartKind;
  history: SnapshotHistory;
}) {
  const repositoryId = history.repositoryId;
  const [metric, setMetric] = useState<SnapshotMetric>("VISITORS");
  const [selected, setSelected] = useState<string[]>([]);
  const [pickerOpen, setPickerOpen] = useState(false);

  useEffect(() => {
    const stored = readSnapshotChartPrefs(repositoryId, kind);
    const available = new Set(history.rows.map((row) => row.key));
    const restored = (stored?.keys ?? []).filter((key) => available.has(key));
    const nextMetric = stored?.metric ?? "VISITORS";
    setMetric(nextMetric);
    setSelected(restored.length > 0 ? restored : defaultTopKeys(history.rows, nextMetric, DEFAULT_LINES));
  }, [history, repositoryId, kind]);

  function persist(nextMetric: SnapshotMetric, nextKeys: string[]) {
    setMetric(nextMetric);
    setSelected(nextKeys);
    writeSnapshotChartPrefs(repositoryId, kind, { metric: nextMetric, keys: nextKeys });
  }

  const option = useMemo(
    () => snapshotChartOption({ history, metric, selected }),
    [history, metric, selected],
  );
  const legend = [...selected, OTHER_KEY];
  const moreCount = Math.max(0, history.rows.length - selected.length);
  const enoughHistory = history.dates.length >= 2;
  const showIcons = kind === "referrers";

  return (
    <Card>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-medium">{title}</h2>
        <div className="inline-flex rounded-lg border bg-muted p-1">
          <MetricButton active={metric === "VISITORS"} onClick={() => persist("VISITORS", selected)}>
            Visitors
          </MetricButton>
          <MetricButton active={metric === "VIEWS"} onClick={() => persist("VIEWS", selected)}>
            Views
          </MetricButton>
        </div>
      </div>
      {!enoughHistory && (
        <div className="rounded-lg border border-dashed px-4 py-10 text-center text-sm text-muted-foreground">
          <div className="font-medium text-foreground">Not enough history yet.</div>
          <div className="mt-1">At least two snapshots are required.</div>
        </div>
      )}
      {enoughHistory && (
        <>
          <div className="mb-3 flex flex-wrap gap-3 text-sm">
            {legend.map((name, index) => (
              <span key={name} className="inline-flex items-center gap-1.5">
                <span className="h-2.5 w-2.5 rounded-full" style={{ background: referrerLineColor(name, index) }} />
                {(showIcons || name === OTHER_KEY) && <ReferrerSourceIcon source={name} />}
                {name}
              </span>
            ))}
          </div>
          <PersistentECharts
            chartId={`${repositoryId}:snapshot-${kind}`}
            series={legend.map((name) => ({ key: name, name }))}
            option={option}
            style={{ height: 320, width: "100%" }}
          />
          <div className="relative mt-3">
            <button
              type="button"
              className="text-sm font-medium text-primary"
              onClick={() => setPickerOpen((open) => !open)}
            >
              {moreCount === 0
                ? kind === "referrers"
                  ? "Select sources"
                  : "Select paths"
                : `+ ${moreCount} more ${kind === "referrers" ? "sources" : "paths"}`}
            </button>
            {pickerOpen && (
              <div className="absolute z-20 mt-2 w-72 rounded-lg border bg-card p-3 shadow-lg">
                <div className="mb-2 text-sm font-medium">
                  {kind === "referrers" ? "Select sources" : "Select paths"}
                </div>
                <div className="max-h-64 space-y-1 overflow-y-auto">
                  {history.rows.map((row) => (
                    <label
                      key={row.key}
                      className="flex cursor-pointer items-center gap-2 rounded-md px-1 py-1 text-sm hover:bg-muted"
                    >
                      <input
                        type="checkbox"
                        checked={selected.includes(row.key)}
                        onChange={() => {
                          const next = selected.includes(row.key)
                            ? selected.filter((key) => key !== row.key)
                            : [...selected, row.key];
                          persist(metric, next);
                        }}
                      />
                      {showIcons && <ReferrerSourceIcon source={row.key} />}
                      <span className="[overflow-wrap:anywhere]">{row.key}</span>
                    </label>
                  ))}
                </div>
              </div>
            )}
          </div>
        </>
      )}
    </Card>
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
