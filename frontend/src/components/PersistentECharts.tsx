import { useEffect, useMemo, useRef, useState, type ComponentProps } from "react";
import ReactECharts from "echarts-for-react";
import type { GrowthEvent } from "../lib/api";
import { readChartSelection, writeChartSelection } from "../lib/chartLegend";
import { chartCsv, chartExportBase, downloadChartCsv, downloadChartPng } from "../lib/chartExport";
import { presentChartOption, type ChartAxis } from "../lib/chartPresentation";
import { markLineEvents } from "../lib/trafficChart";
import { useChartMode, useRegisterChartExport } from "./ChartDownloadMenu";

type SeriesRef = { key: string; name: string };

type Props = {
  chartId: string;
  series: readonly SeriesRef[];
  /** Rank History uses the query table instead of the legend cookie. */
  persistSelection?: boolean;
} & ComponentProps<typeof ReactECharts>;

export function ChartModePlot({
  build,
  ...rest
}: {
  build: (mode: { axis: ChartAxis; events: GrowthEvent[] }) => unknown;
} & Omit<Props, "option">) {
  const mode = useChartMode();
  const option = useMemo(
    () => build({ axis: mode.axis, events: mode.events }),
    [build, mode.axis, mode.events],
  );
  return <PersistentECharts {...rest} option={option} />;
}

export function PersistentECharts({ chartId, series, option, onEvents, persistSelection = true, ...rest }: Props) {
  const chartRef = useRef<ReactECharts>(null);
  const mode = useChartMode();
  const [stored, setStored] = useState<Record<string, boolean> | null>(() => readChartSelection(chartId));

  useEffect(() => {
    setStored(readChartSelection(chartId));
  }, [chartId]);

  const selectedByName = useMemo(() => {
    const selected: Record<string, boolean> = {};
    for (const item of series) {
      selected[item.name] = !persistSelection || !(stored && Object.prototype.hasOwnProperty.call(stored, item.key))
        ? true
        : stored[item.key];
    }
    return selected;
  }, [series, stored, persistSelection]);

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

  const presented = useMemo(
    () => presentChartOption(mergedOption, { axis: mode.axis, events: mode.events, period: mode.period }),
    [mergedOption, mode.axis, mode.events, mode.period],
  );

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
  useRegisterChartExport({
    downloadPng: () => downloadChartPng(chartRef.current, `${fileBase}.png`),
    downloadCsv: () => downloadChartCsv(`${fileBase}.csv`, chartCsv(presented)),
  });

  return (
    <ReactECharts
      option={presented}
      notMerge
      onEvents={{
        ...onEvents,
        ...(persistSelection ? { legendselectchanged: onLegendSelectChanged } : {}),
        click: (params: { componentType?: string; data?: { events?: GrowthEvent[] } }) => {
          const clicked = markLineEvents(params);
          if (clicked.length > 0) {
            mode.openMarker(clicked);
          }
          const click = onEvents?.click as ((value: unknown) => void) | undefined;
          click?.(params);
        },
      }}
      {...rest}
      ref={chartRef}
    />
  );
}
