import { useDashboardView } from "../lib/dashboardView";
import type { Period } from "./PeriodSelector";
import { useChartVisibility } from "../lib/chartVisibility";
import { snapshotSeriesKey } from "../lib/snapshotChart";
import { useState } from "react";
import type { SnapshotHistory } from "../lib/api";
import { defaultTopKeys } from "../lib/snapshotHistory";
import { readSnapshotChartPrefs, writeSnapshotChartPrefs, type SnapshotChartKind, type SnapshotMetric } from "../lib/snapshotChartPrefs";
import { SnapshotHistoryChart } from "./SnapshotHistoryChart";
import { SnapshotHistoryTable } from "./SnapshotHistoryTable";

export function SnapshotHistoryView({ title, kind, history, period = "all", displayLabel }: { title: string; kind: SnapshotChartKind; history: SnapshotHistory; period?: Period; displayLabel?: (key:string)=>string }) {
  const view=useDashboardView();
  const [prefs, setPrefs] = useState(() => readSnapshotChartPrefs(history.repositoryId, kind) ?? { metric: "VISITORS" as SnapshotMetric, keys: defaultTopKeys(history.rows, "VISITORS", 5) });
  const visibility = useChartVisibility(view.public?`${view.scopeKey}:snapshot-${kind}`:`${history.repositoryId}:snapshot-${kind}`);
  function change(metric: SnapshotMetric, keys: string[]) {
    const next = { metric, keys };
    setPrefs(next);
    writeSnapshotChartPrefs(history.repositoryId, kind, next);
  }
  function toggle(key: string) {
    if (prefs.keys.includes(key) && !visibility.isVisible(snapshotSeriesKey(history, key))) { visibility.reveal([snapshotSeriesKey(history, key)]); return; }
    change(prefs.metric, prefs.keys.includes(key) ? prefs.keys.filter(item => item !== key) : [...prefs.keys, key]);
  }
  return <>
    <SnapshotHistoryChart period={period} title={title} kind={kind} history={history} selected={prefs.keys} metric={prefs.metric} onChange={change} displayLabel={displayLabel} />
    <SnapshotHistoryTable kind={kind} history={history} selected={prefs.keys.filter(key => visibility.isVisible(snapshotSeriesKey(history, key)))} onToggle={toggle} displayLabel={displayLabel} />
  </>;
}
