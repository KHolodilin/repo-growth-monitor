import { useEffect, useId, useMemo, useRef, useState, type ComponentProps } from "react";
import { seriesStyle } from "../lib/seriesStyle";
import { SeriesSwatch } from "./SeriesSwatch";
import { ChartDataAlternative } from "./ChartDataAlternative";
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
  accessibleTitle?: string;
  exportTitle?: string;
  dataAlternative?: boolean;
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

export function PersistentECharts({ chartId, series, option, onEvents, persistSelection = true, accessibleTitle, exportTitle, dataAlternative = true, style, ...rest }: Props) {
  const descriptionId = useId();
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
      selected[item.name] = Object.prototype.hasOwnProperty.call(stored, item.key) ? stored[item.key] : (item.key === "Total Clones" || item.key === "Unique Cloners" ? false : true);
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
      return mode.mobile || accessibleTitle
        ? { ...option, legend: { show: false, selected: selectedByName } }
        : option;
    }
    return {
      ...option,
      legend: mode.mobile || accessibleTitle
        ? { ...legend, show: false, selected: selectedByName }
        : { ...legend, selected: selectedByName },
    };
  }, [option, selectedByName, mode.mobile, accessibleTitle]);

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
    mode.setAxisDates((presented as { eventAxisDates?: string[] }).eventAxisDates ?? axis.data.map((value) => String(value)));
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
    downloadPng: () => downloadChartPng(chartRef.current, `${fileBase}.png`, exportTitle ?? accessibleTitle),
    downloadCsv: () => downloadChartCsv(`${fileBase}.csv`, chartCsv(presented)),
  });

  const shown = series.filter(item => selectedByName[item.name]).map(item => item.name);
  function toggleSeries(name: string) {
    onLegendSelectChanged({ selected: { ...selectedByName, [name]: !selectedByName[name] } });
  }
  return (
    <div role="group" aria-label={accessibleTitle ?? `${chartId} chart`}>
      {accessibleTitle && !mode.mobile && <div role="group" aria-label={`${accessibleTitle} series`} className="mb-3 flex flex-wrap justify-center gap-2">
        {series.map(item => <button key={item.key} type="button" aria-pressed={selectedByName[item.name]} onClick={() => toggleSeries(item.name)} className="inline-flex min-h-10 items-center gap-1.5 rounded border px-3 py-1 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">
          <SeriesSwatch style={seriesStyle(item.name)} />{item.name}<span aria-hidden="true">{selectedByName[item.name] ? "✓" : "—"}</span>
        </button>)}
      </div>}
      <p id={descriptionId} className="sr-only">{accessibleTitle ?? chartId}. Period: {mode.period ?? "all"}. Visible series: {shown.join(", ") || "none"}. Historical values are available below the chart.</p>
      {accessibleTitle && shown.length === 0 && <p role="status" className="py-3 text-center text-sm text-muted-foreground">No series selected. Choose a metric to display the chart.</p>}
      <div role="img" aria-label={`${accessibleTitle ?? chartId} graph`} aria-describedby={descriptionId}>
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
      </div>
      {accessibleTitle && dataAlternative && <ChartDataAlternative option={presented} title={accessibleTitle} />}
    </div>
  );
}
