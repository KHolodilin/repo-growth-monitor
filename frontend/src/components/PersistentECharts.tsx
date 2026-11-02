import { useEffect, useMemo, useRef, useState, type ComponentProps } from "react";
import ReactECharts from "echarts-for-react";
import type { GrowthEvent } from "../lib/api";
import { defaultChartSelection, readChartSelection, writeChartSelection } from "../lib/chartLegend";
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

export function PersistentECharts({ chartId, series, option, onEvents, persistSelection = true, style, ...rest }: Props) {
  const chartRef = useRef<ReactECharts>(null);
  const mode = useChartMode();
  const [stored, setStored] = useState<Record<string, boolean> | null>(() => readChartSelection(chartId));

  useEffect(() => {
    setStored(readChartSelection(chartId));
  }, [chartId]);

  const selectedByName = useMemo(() => {
    const defaults = defaultChartSelection(series.map((item) => item.key));
    const selected: Record<string, boolean> = {};
    for (const item of series) {
      if (mode.mobile && mode.mobileSeriesName) {
        selected[item.name] = item.name === mode.mobileSeriesName;
        continue;
      }
      if (!persistSelection) {
        selected[item.name] = true;
        continue;
      }
      if (!stored) {
        selected[item.name] = defaults[item.key];
        continue;
      }
      selected[item.name] = Object.prototype.hasOwnProperty.call(stored, item.key) ? stored[item.key] : true;
    }
    if (mode.mobile) {
      selected.Events = false;
    }
    return selected;
  }, [series, stored, persistSelection, mode.mobile, mode.mobileSeriesName]);

  const mergedOption = useMemo(() => {
    const base = option as { legend?: unknown; grid?: Record<string, unknown> };
    const legend = base.legend;
    if (!legend || typeof legend !== "object" || Array.isArray(legend)) {
      return mode.mobile
        ? { ...option, legend: { show: false, selected: selectedByName } }
        : option;
    }
    return {
      ...option,
      legend: mode.mobile
        ? { ...legend, show: false, selected: selectedByName }
        : { ...legend, selected: selectedByName },
    };
  }, [option, selectedByName, mode.mobile]);

  const presented = useMemo(
    () =>
      presentChartOption(mergedOption, {
        axis: mode.axis,
        events: mode.events,
        period: mode.period,
        mobile: mode.mobile,
      }),
    [mergedOption, mode.axis, mode.events, mode.period, mode.mobile],
  );

  useEffect(() => {
    const axis = (presented as { xAxis?: { data?: unknown } | unknown[] }).xAxis;
    if (!axis || Array.isArray(axis) || !Array.isArray(axis.data)) {
      mode.setAxisDates([]);
      return;
    }
    mode.setAxisDates(axis.data.map((value) => String(value)));
  }, [presented, mode.setAxisDates]);

  const plotStyle = mode.mobile
    ? { ...(typeof style === "object" && style ? style : {}), height: 220, width: "100%" }
    : style;

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
      style={plotStyle}
      onEvents={{
        ...onEvents,
        ...(persistSelection && !mode.mobile ? { legendselectchanged: onLegendSelectChanged } : {}),
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
