import { useState } from "react";
import type { SnapshotHistory } from "../lib/api";
import { defaultTopKeys } from "../lib/snapshotHistory";
import { readSnapshotChartPrefs, writeSnapshotChartPrefs, type SnapshotChartKind, type SnapshotMetric } from "../lib/snapshotChartPrefs";
import { SnapshotHistoryChart } from "./SnapshotHistoryChart";
import { SnapshotHistoryTable } from "./SnapshotHistoryTable";

export function SnapshotHistoryView({ title, kind, history }: { title: string; kind: SnapshotChartKind; history: SnapshotHistory }) {
  const [prefs, setPrefs] = useState(() => readSnapshotChartPrefs(history.repositoryId, kind) ?? { metric: "VISITORS" as SnapshotMetric, keys: defaultTopKeys(history.rows, "VISITORS", 5) });
  function change(metric: SnapshotMetric, keys: string[]) {
    const next = { metric, keys };
    setPrefs(next);
    writeSnapshotChartPrefs(history.repositoryId, kind, next);
  }
  function toggle(key: string) {
    change(prefs.metric, prefs.keys.includes(key) ? prefs.keys.filter(item => item !== key) : [...prefs.keys, key]);
  }
  return <>
    <SnapshotHistoryChart title={title} kind={kind} history={history} selected={prefs.keys} metric={prefs.metric} onChange={change} />
    <SnapshotHistoryTable kind={kind} history={history} selected={prefs.keys} onToggle={toggle} />
  </>;
}
