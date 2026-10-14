import { useEffect, useMemo, useRef, useState, type ComponentProps } from "react";
import ReactECharts from "echarts-for-react";
import { readChartSelection, writeChartSelection } from "../lib/chartLegend";
import { chartCsv, chartExportBase, downloadChartCsv, downloadChartPng } from "../lib/chartExport";

type SeriesRef = { key: string; name: string };

type Props = {
  chartId: string;
  series: readonly SeriesRef[];
} & ComponentProps<typeof ReactECharts>;

export function PersistentECharts({ chartId, series, option, onEvents, ...rest }: Props) {
  const chartRef = useRef<ReactECharts>(null);
  const [stored, setStored] = useState<Record<string, boolean> | null>(() => readChartSelection(chartId));

  useEffect(() => {
    setStored(readChartSelection(chartId));
  }, [chartId]);

  const selectedByName = useMemo(() => {
    const selected: Record<string, boolean> = {};
    for (const item of series) {
      selected[item.name] = stored && Object.prototype.hasOwnProperty.call(stored, item.key) ? stored[item.key] : true;
    }
    return selected;
  }, [series, stored]);

  const mergedOption = useMemo(() => {
    const legend = (option as { legend?: unknown }).legend;
    if (!legend || typeof legend !== "object" || Array.isArray(legend)) {
      return option;
    }
    return {
      ...option,
      legend: { ...legend, selected: selectedByName },
    };
  }, [option, selectedByName]);

  function onLegendSelectChanged(params: { selected?: Record<string, boolean> }) {
    const byName = params.selected ?? {};
    const next: Record<string, boolean> = {};
    for (const item of series) {
      next[item.key] = byName[item.name] !== false;
    }
    writeChartSelection(chartId, next);
    setStored(next);
  }

  const fileBase = chartExportBase(chartId);

  return (
    <div>
      <div className="mb-2 flex justify-end gap-2">
        <button
          type="button"
          className="rounded-md border bg-card px-2.5 py-1 text-xs font-medium hover:bg-muted"
          aria-label="Download PNG"
          onClick={() => downloadChartPng(chartRef.current, `${fileBase}.png`)}
        >
          PNG
        </button>
        <button
          type="button"
          className="rounded-md border bg-card px-2.5 py-1 text-xs font-medium hover:bg-muted"
          aria-label="Download CSV"
          onClick={() => downloadChartCsv(`${fileBase}.csv`, chartCsv(mergedOption))}
        >
          CSV
        </button>
      </div>
      <ReactECharts
        option={mergedOption}
        notMerge
        onEvents={{ ...onEvents, legendselectchanged: onLegendSelectChanged }}
        {...rest}
        ref={chartRef}
      />
    </div>
  );
}
