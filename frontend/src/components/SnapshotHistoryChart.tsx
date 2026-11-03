import { useMemo, useState } from "react";
import type { SnapshotHistory } from "../lib/api";
import { snapshotChartOption } from "../lib/snapshotChart";
import {
  type SnapshotChartKind,
  type SnapshotMetric,
} from "../lib/snapshotChartPrefs";
import { OTHER_KEY, chartValue } from "../lib/snapshotHistory";
import { useIsMobileChart } from "../lib/useMediaQuery";
import { cn } from "../lib/utils";
import { ChartCard } from "./ChartDownloadMenu";
import { PersistentECharts } from "./PersistentECharts";
import { ReferrerSourceIcon, referrerLineColor } from "./ReferrerSourceIcon";


export function SnapshotHistoryChart({
  title,
  kind,
  history,
  selected, metric, onChange,
}: {
  title: string;
  kind: SnapshotChartKind;
  history: SnapshotHistory;
  selected: string[];
  metric: SnapshotMetric;
  onChange: (metric: SnapshotMetric, keys: string[]) => void;
}) {
  const repositoryId = history.repositoryId;
  const mobile = useIsMobileChart();
  const [pickerOpen, setPickerOpen] = useState(false);
  const persist = onChange;
  const option = useMemo(
    () => snapshotChartOption({ history, metric, selected }),
    [history, metric, selected],
  );
  const legend = [...selected, OTHER_KEY];
  const mobileSeries = useMemo(
    () => [...selected, OTHER_KEY].map((name) => ({ key: name, name })),
    [selected],
  );
  const moreCount = Math.max(0, history.rows.length - selected.length);
  const enoughHistory = history.dates.length >= 2;
  const hasData = history.rows.some(row => selected.includes(row.key) && row.cells.some(cell => chartValue(cell, metric) !== null));
  const showIcons = kind === "referrers";
  const metricControls = (
    <div className="inline-flex rounded-lg border bg-muted p-1">
      <MetricButton active={metric === "VISITORS"} onClick={() => persist("VISITORS", selected)}>
        Visitors
      </MetricButton>
      <MetricButton active={metric === "VIEWS"} onClick={() => persist("VIEWS", selected)}>
        Views
      </MetricButton>
    </div>
  );

  return (
    <ChartCard
      title={title}
      repositoryId={repositoryId}
      period="all"
      context={`repository:${repositoryId}:${kind}`}
      controls={metricControls}
      mobileControls={metricControls}
      mobileSeries={mobileSeries}
    >
      {!enoughHistory && (
        <div className="rounded-lg border border-dashed px-4 py-10 text-center text-sm text-muted-foreground">
          <div className="font-medium text-foreground">Not enough history yet.</div>
          <div className="mt-1">At least two snapshots are required.</div>
        </div>
      )}
      {enoughHistory && (
        <>
          {selected.length === 0 ? <p className="py-6 text-sm text-muted-foreground">No {kind === "referrers" ? "sources" : "paths"} selected. Select items to display history.</p> : !hasData ? <p className="py-6 text-sm text-muted-foreground">No data for the selected period.</p> : (

        <>
          {!mobile && (
            <div className="mb-3 flex flex-wrap gap-3 text-sm">
              {legend.map((name, index) => (
                <span key={name} className="inline-flex items-center gap-1.5">
                  <span className="h-2.5 w-2.5 rounded-full" style={{ background: referrerLineColor(name, index) }} />
                  {(showIcons || name === OTHER_KEY) && <ReferrerSourceIcon source={name} />}
                  {name}
                </span>
              ))}
            </div>
          )}
          <PersistentECharts
            chartId={`${repositoryId}:snapshot-${kind}`}
            series={legend.map((name) => ({ key: name, name }))}
            option={option}
            style={{ height: 320, width: "100%" }}
          />
        </>)}
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
              <div className="absolute z-20 mt-2 w-full max-w-72 rounded-lg border bg-card p-3 shadow-lg">
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
    </ChartCard>
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
        active ? "bg-card text-foreground shadow-xs" : "text-muted-foreground hover:text-foreground",
      )}
      onClick={onClick}
    >
      {children}
    </button>
  );
}
