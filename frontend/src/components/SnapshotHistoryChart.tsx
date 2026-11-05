import { useChartVisibility } from "../lib/chartVisibility";
import { useMemo, useState } from "react";
import type { SnapshotHistory } from "../lib/api";
import { snapshotChartOption, snapshotSeriesKey } from "../lib/snapshotChart";
import {
  type SnapshotChartKind,
  type SnapshotMetric,
} from "../lib/snapshotChartPrefs";
import { OTHER_KEY, chartValue } from "../lib/snapshotHistory";
import { cn } from "../lib/utils";
import { ChartCard } from "./ChartDownloadMenu";
import { PersistentECharts } from "./PersistentECharts";
import { ReferrerSourceIcon } from "./ReferrerSourceIcon";


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
  const visibility = useChartVisibility(`${repositoryId}:snapshot-${kind}`);
  const [pickerOpen, setPickerOpen] = useState(false);
  const persist = onChange;
  const showIcons = kind === "referrers";
  const option = useMemo(
    () => snapshotChartOption({ history, metric, selected }),
    [history, metric, selected],
  );
  const legend = [...selected, OTHER_KEY];
  const moreCount = Math.max(0, history.rows.length - selected.length);
  const enoughHistory = history.dates.length >= 1;
  const hasData = history.rows.some(row => selected.includes(row.key) && row.cells.some(cell => chartValue(cell, metric) !== null));
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
      mobileSeries={[]}
    >
      <p className="mb-3 text-xs text-muted-foreground">Values reported for GitHub’s rolling window at each snapshot date, not exact daily traffic. Other sums the remaining sources/paths; Visitors are not deduplicated across sources.</p>
      {!enoughHistory && (
        <div className="rounded-lg border border-dashed px-4 py-10 text-center text-sm text-muted-foreground">
          <div className="font-medium text-foreground">No recorded snapshot values.</div>
          <div className="mt-1">A recorded snapshot is needed to display history.</div>
        </div>
      )}
      {enoughHistory && (
        <>
          {selected.length === 0 ? <p className="py-6 text-sm text-muted-foreground">No {kind === "referrers" ? "sources" : "paths"} selected. Select items to display history.</p> : !hasData ? <p className="py-6 text-sm text-muted-foreground">No data for the selected period.</p> : (

        <>
          <PersistentECharts
            chartId={`${repositoryId}:snapshot-${kind}`}
            series={legend.map((name) => ({ key: snapshotSeriesKey(history, name), name, icon: kind === "referrers" || name === OTHER_KEY ? <ReferrerSourceIcon source={name} /> : undefined }))}
            option={option}
            exportTitle={title}
            persistSelection={false}
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
                        checked={selected.includes(row.key) && visibility.isVisible(snapshotSeriesKey(history, row.key))}
                        onChange={() => {
                          if (selected.includes(row.key) && !visibility.isVisible(snapshotSeriesKey(history, row.key))) { visibility.reveal([snapshotSeriesKey(history, row.key)]); return; }
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
