import { publishChartVisibility, readChartVisibility, subscribeChartVisibility } from "../lib/chartVisibility";
import { useEffect, useId, useMemo, useRef, useState, type ComponentProps, type ReactNode } from "react";
import { metricColor, seriesStyle } from "../lib/seriesStyle";
import { SeriesSwatch } from "./SeriesSwatch";
import { ChartDataAlternative } from "./ChartDataAlternative";
import ReactECharts from "echarts-for-react";
import type { GrowthEvent } from "../lib/api";
import { defaultChartSelection, readChartSelection, writeChartSelection } from "../lib/chartLegend";
import { chartCsv, chartExportBase, downloadChartCsv, downloadChartPng } from "../lib/chartExport";
import { presentChartOption, type ChartAxis } from "../lib/chartPresentation";
import { markLineEvents } from "../lib/trafficChart";
import { useChartMode, useRegisterChartExport } from "./ChartDownloadMenu";

type SeriesRef = { key: string; name: string; icon?: ReactNode };

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
  const wrapperRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<ReactECharts>(null);
  const mode = useChartMode();
  const [stored, setStored] = useState<Record<string, boolean> | null>(() => readChartVisibility(chartId) ?? (persistSelection ? readChartSelection(chartId) : null));

  useEffect(() => {
    setStored(readChartVisibility(chartId) ?? (persistSelection ? readChartSelection(chartId) : null));
    return subscribeChartVisibility(chartId, () => setStored(readChartVisibility(chartId)));
  }, [chartId, persistSelection]);

  const selectedByName = useMemo(() => {
    const defaults = defaultChartSelection(series.map((item) => item.key));
    const selected: Record<string, boolean> = {};
    for (const item of series) {
      if (mode.mobile && mode.mobileSeriesName) {
        selected[item.name] = item.name === mode.mobileSeriesName;
        continue;
      }
      if (!persistSelection) {
        selected[item.name] = stored?.[item.key] !== false;
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

  useEffect(() => {
    if (!mode.mobile || !mode.mobileSeriesName || persistSelection) return;
    const active = series.find(item => item.name === mode.mobileSeriesName);
    if (active && readChartVisibility(chartId)?.[active.key] === false) {
      publishChartVisibility(chartId, { ...readChartVisibility(chartId), [active.key]: true });
    }
  }, [mode.mobile, mode.mobileSeriesName, persistSelection, series, chartId]);

  const mergedOption = useMemo(() => ({
    ...option,
    legend: { show: false, data: series.map(item => item.name), selected: selectedByName },
  }), [option, selectedByName, series]);

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
    if (persistSelection) writeChartSelection(chartId, next);
    publishChartVisibility(chartId, next);
    setStored(next);
  }

  useEffect(() => {
    const hide = () => chartRef.current?.getEchartsInstance?.()?.dispatchAction?.({ type: "hideTip" });
    const outside = (event: PointerEvent) => { if (!wrapperRef.current?.contains(event.target as Node)) hide(); };
    hide();
    document.addEventListener("pointerdown", outside, true);
    window.addEventListener("scroll", hide, true);
    window.addEventListener("resize", hide);
    return () => {
      hide();
      document.removeEventListener("pointerdown", outside, true);
      window.removeEventListener("scroll", hide, true);
      window.removeEventListener("resize", hide);
    };
  }, [presented, chartId]);

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
    <div ref={wrapperRef} role="group" aria-label={accessibleTitle ?? `${chartId} chart`}>
      {!(mode.mobile && mode.mobileSeriesName) && <div role="group" aria-label={`${accessibleTitle ?? chartId} series`} className="mb-3 flex flex-wrap justify-center gap-2">
        {series.map(item => <button key={item.key} type="button" aria-pressed={selectedByName[item.name]} onClick={() => toggleSeries(item.name)} className={`inline-flex min-h-11 max-w-full min-w-0 items-center gap-1.5 rounded border px-3 py-1 text-left text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary ${selectedByName[item.name] ? "bg-muted" : "opacity-60"}`}>
          <SeriesSwatch style={seriesStyle(metricColor(item.name) ? item.name : item.key)} />{item.icon}<span className="min-w-0 [overflow-wrap:anywhere]">{item.name}</span><span aria-hidden="true">{selectedByName[item.name] ? "✓" : "—"}</span>
        </button>)}
      </div>}
      <p id={descriptionId} className="sr-only">{accessibleTitle ?? chartId}. Period: {mode.period ?? "all"}. Visible series: {shown.join(", ") || "none"}. Historical values are available below the chart.</p>
      {shown.length === 0 && <p role="status" className="py-3 text-center text-sm text-muted-foreground">No series selected. Choose a metric to display the chart.</p>}
      <div role="img" aria-label={`${accessibleTitle ?? chartId} graph`} aria-describedby={descriptionId}>
    <ReactECharts
      option={presented}
      notMerge
      style={plotStyle}
      onEvents={{
        ...onEvents,
        legendselectchanged: onLegendSelectChanged,
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
