import type { Period } from "./PeriodSelector";
import { createContext, useCallback, useContext, useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, type GrowthEvent } from "../lib/api";
import { readChartAxis, readChartEventFilter, subscribeChartMode, writeChartAxis, writeChartEventFilter } from "../lib/chartModePrefs";
import { defaultMobileSeriesName } from "../lib/chartLegend";
import { asGrowthEvents, eventUtcDate, filterGrowthEvents, groupEventsByUtcDate, type EventFilter } from "../lib/growthEvents";
import type { ChartAxis } from "../lib/chartPresentation";
import { useIsMobileChart } from "../lib/useMediaQuery";
import { metricColor, prepareSeriesStyles, seriesStyle } from "../lib/seriesStyle";
import { SeriesSwatch } from "./SeriesSwatch";
import { Card } from "./ui";
import { EventDetailsDialog } from "./GrowthEventsPanel";

type ExportActions = {
  downloadPng: () => void;
  downloadCsv: () => void;
};

type ExportRegistry = {
  current: ExportActions | null;
  subscribe: (listener: () => void) => () => void;
  publish: (next: ExportActions | null) => void;
};

type MobileSeriesOption = { key: string; name: string };

type ChartMode = {
  eventFilter: EventFilter;
  setEventFilter: (filter: EventFilter) => void;
  axis: ChartAxis;
  setAxis: (axis: ChartAxis) => void;
  events: GrowthEvent[];
  period?: Period;
  openMarker: (events: GrowthEvent[]) => void;
  mobile: boolean;
  mobileSeriesName: string | null;
  axisDates: string[];
  setAxisDates: (dates: string[]) => void;
};

const ChartExportContext = createContext<ExportRegistry | null>(null);
const ChartModeContext = createContext<ChartMode | null>(null);

const EVENT_MODES = [
  ["all", "All", "All", "All events"],
  ["github", "GH", "GitHub", "GitHub events"],
  ["promotion", "Pro", "Promotion", "Promotion events"],
  ["off", "Off", "Off", "Events off"],
] as const;

function createRegistry(): ExportRegistry {
  let current: ExportActions | null = null;
  const listeners = new Set<() => void>();
  return {
    get current() {
      return current;
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    publish(next) {
      current = next;
      for (const listener of listeners) {
        listener();
      }
    },
  };
}

export function ChartExportProvider({ children }: { children: ReactNode }) {
  const [registry] = useState(createRegistry);
  return <ChartExportContext.Provider value={registry}>{children}</ChartExportContext.Provider>;
}

export function useChartMode() {
  const mode = useContext(ChartModeContext);
  return {
    eventFilter: mode?.eventFilter ?? ("off" as const),
    axis: mode?.axis ?? ("calendar" as const),
    events: mode?.events ?? [],
    period: mode?.period,
    openMarker: mode?.openMarker ?? (() => undefined),
    mobile: mode?.mobile ?? false,
    mobileSeriesName: mode?.mobileSeriesName ?? null,
    axisDates: mode?.axisDates ?? [],
    setAxisDates: mode?.setAxisDates ?? (() => undefined),
  };
}

export function useRegisterChartExport(actions: ExportActions) {
  const registry = useContext(ChartExportContext);
  const latest = useRef(actions);
  latest.current = actions;
  useLayoutEffect(() => {
    if (!registry) {
      return;
    }
    registry.publish({
      downloadPng: () => latest.current.downloadPng(),
      downloadCsv: () => latest.current.downloadCsv(),
    });
    return () => {
      registry.publish(null);
    };
  }, [registry]);
}

export function ChartCard({
  title,
  controls,
  mobileControls,
  children,
  events: eventsProp,
  repositoryId,
  period = "7d",
  context,
  onEventsChanged,
  mobileSeries,
  selectedMobileSeries,
  onMobileSeriesChange,
}: {
  title: string;
  controls?: ReactNode;
  /** Extra mobile-only controls (metric switch) under the title row. */
  mobileControls?: ReactNode;
  children: ReactNode;
  events?: GrowthEvent[];
  repositoryId?: number;
  period?: Period;
  /** Where this chart lives. The All and Calendar choice is stored for this place only. */
  context?: string;
  onEventsChanged?: () => void;
  /** When set (even empty), mobile (&lt;768px) uses the one-series ChartCard shell. */
  mobileSeries?: readonly MobileSeriesOption[];
  selectedMobileSeries?: string;
  onMobileSeriesChange?: (name: string) => void;
}) {
  const mobile = useIsMobileChart();
  const mobileShell = mobile && mobileSeries != null;
  const [loaded, setLoaded] = useState<GrowthEvent[]>([]);
  const [reloadKey, setReloadKey] = useState(0);
  const [eventFilter, setEventFilterState] = useState<EventFilter>(() => (context ? readChartEventFilter(context) : "off"));
  const [axis, setAxisState] = useState<ChartAxis>(() => (context ? readChartAxis(context) : "calendar"));
  const [marker, setMarker] = useState<GrowthEvent[] | null>(null);
  const [mobileSeriesName, setMobileSeriesName] = useState<string | null>(() =>
    mobileSeries ? defaultMobileSeriesName(mobileSeries) : null,
  );
  const effectiveMobileSeriesName = selectedMobileSeries && mobileSeries?.some(item => item.name === selectedMobileSeries) ? selectedMobileSeries : mobileSeriesName;
  const [axisDates, setAxisDatesState] = useState<string[]>([]);
  const setAxisDates = useCallback((dates: string[]) => {
    setAxisDatesState((prev) =>
      prev.length === dates.length && prev.every((date, index) => date === dates[index]) ? prev : dates,
    );
  }, []);
  useEffect(() => {
    if (!mobileSeries?.length) {
      setMobileSeriesName(null);
      return;
    }
    setMobileSeriesName((current) => {
      if (current != null && mobileSeries.some((item) => item.name === current)) {
        return current;
      }
      return defaultMobileSeriesName(mobileSeries);
    });
  }, [mobileSeries]);
  useEffect(() => {
    if (!context) {
      return;
    }
    setEventFilterState(readChartEventFilter(context));
    setAxisState(readChartAxis(context));
    return subscribeChartMode(context, () => {
      setEventFilterState(readChartEventFilter(context));
      setAxisState(readChartAxis(context));
    });
  }, [context]);
  const setEventFilter = useCallback((value: EventFilter) => {
    if (!context) {
      setEventFilterState(value);
      return;
    }
    writeChartEventFilter(context, value);
  }, [context]);
  const setAxis = useCallback((value: ChartAxis) => {
    if (!context) {
      setAxisState(value);
      return;
    }
    writeChartAxis(context, value);
  }, [context]);

  useEffect(() => {
    if (eventsProp !== undefined || repositoryId == null) {
      return;
    }
    let cancelled = false;
    Promise.resolve(api<GrowthEvent[]>(`/api/v1/repositories/${repositoryId}/growth-events?period=${period}`))
      .then((rows) => {
        if (!cancelled) {
          setLoaded(asGrowthEvents(rows));
        }
      })
      .catch(() => {
        if (!cancelled) {
          setLoaded([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [eventsProp, repositoryId, period, reloadKey]);

  const source = eventsProp !== undefined ? asGrowthEvents(eventsProp) : loaded;
  const events = useMemo(() => filterGrowthEvents(source, eventFilter), [source, eventFilter]);
  const openMarker = useCallback((items: GrowthEvent[]) => {
    setMarker(items);
  }, []);
  const mode = useMemo<ChartMode>(
    () => ({
      eventFilter,
      setEventFilter,
      axis,
      setAxis,
      events,
      period,
      openMarker,
      mobile: mobileShell,
      mobileSeriesName: mobileShell && (mobileSeries?.length ?? 0) > 1 ? effectiveMobileSeriesName : null,
      axisDates,
      setAxisDates,
    }),
    [eventFilter, setEventFilter, axis, setAxis, events, period, openMarker, mobileShell, mobileSeries?.length, effectiveMobileSeriesName, axisDates],
  );

  return (
    <ChartExportProvider>
      <ChartModeContext.Provider value={mode}>
        <Card>
          {mobileShell ? (
            <div className="mb-3 space-y-3">
              <div className="flex items-center justify-between gap-3">
                <h2 className="font-medium">{title}</h2>
                <MobileChartMenu />
              </div>
              {mobileControls}
              {mobileSeries && mobileSeries.length > 1 && effectiveMobileSeriesName && (
                <SeriesSegment options={mobileSeries} value={effectiveMobileSeriesName} onChange={name => { setMobileSeriesName(name); onMobileSeriesChange?.(name); }} />
              )}
              <EventsToggle
                on={eventFilter !== "off"}
                onChange={(next) => setEventFilter(next ? "all" : "off")}
              />
            </div>
          ) : (
            <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
              <h2 className="font-medium">{title}</h2>
              <div className="flex flex-nowrap items-center justify-end gap-2">
                {controls}
                <ChartDownloadMenu />
              </div>
            </div>
          )}
          <div className={mobileShell ? "-mx-4" : undefined}>{children}</div>
          {mobileShell && eventFilter !== "off" && (
            <MobileEventStrip events={events} dates={axisDates} onOpen={openMarker} />
          )}
        </Card>
        {marker && marker.length > 0 && (
          <EventDetailsDialog
            repositoryId={marker[0].repositoryId}
            events={marker}
            onClose={() => setMarker(null)}
            onEdit={() => setMarker(null)}
            onChanged={() => {
              setMarker(null);
              onEventsChanged?.();
              setReloadKey((value) => value + 1);
            }}
          />
        )}
      </ChartModeContext.Provider>
    </ChartExportProvider>
  );
}

function SeriesSegment({
  options,
  value,
  onChange,
}: {
  options: readonly MobileSeriesOption[];
  value: string;
  onChange: (name: string) => void;
}) {
  prepareSeriesStyles(options.map(item => metricColor(item.name) ? item.name : item.key));
  return (
    <div role="tablist" aria-label="Chart series" className={options.every(item => metricColor(item.name)) ? "grid grid-cols-2 gap-2" : "grid grid-cols-1 gap-2"}>
      {options.map((item) => {
        const active = item.name === value;
        return (
          <button
            key={item.key}
            type="button"
            role="tab"
            aria-selected={active}
            className={
              active
                ? "inline-flex min-h-11 min-w-0 items-center justify-center gap-1 rounded-md bg-primary px-2 py-1.5 text-sm font-medium text-primary-foreground"
                : "inline-flex min-h-11 min-w-0 items-center justify-center gap-1 rounded-md px-2 py-1.5 text-sm text-muted-foreground hover:bg-muted hover:text-foreground"
            }
            onClick={() => onChange(item.name)}
          >
            <SeriesSwatch style={seriesStyle(metricColor(item.name) ? item.name : item.key)} /><span className="min-w-0 whitespace-normal text-left [overflow-wrap:anywhere]">{item.name}</span>
          </button>
        );
      })}
    </div>
  );
}

function EventsToggle({ on, onChange }: { on: boolean; onChange: (next: boolean) => void }) {
  return (
    <label className="inline-flex items-center gap-2 text-sm text-muted-foreground">
      <input
        type="checkbox"
        className="h-4 w-4 rounded border"
        checked={on}
        onChange={(event) => onChange(event.target.checked)}
      />
      Events
    </label>
  );
}

function MobileEventStrip({
  events,
  dates,
  onOpen,
}: {
  events: GrowthEvent[];
  dates: string[];
  onOpen: (items: GrowthEvent[]) => void;
}) {
  const axis = dates.length > 0 ? dates : [...new Set(events.map(eventUtcDate))].sort();
  const onAxis = new Set(axis);
  const groups = groupEventsByUtcDate(events).filter((group) => onAxis.has(group.date));
  if (axis.length === 0 || groups.length === 0) {
    return <p role="status" className="mt-2 text-xs text-muted-foreground">No events in the displayed range.</p>;
  }
  const first = axis[0];
  const last = axis[axis.length - 1];
  const span = Math.max(1, axis.length - 1);
  return (
    <div className="relative mt-2 h-11 border-t pt-1" aria-label="Growth events">
      {groups.map((group) => {
        const index = axis.indexOf(group.date);
        const left = axis.length === 1 ? 50 : (index / span) * 100;
        return (
          <button
            key={group.date}
            type="button"
            className="absolute top-0 flex h-11 w-11 -translate-x-1/2 items-center justify-center"
            style={{ left: `clamp(22px, ${left}%, calc(100% - 22px))` }}
            aria-label={`${group.events.length} event${group.events.length === 1 ? "" : "s"} on ${group.date}`}
            onClick={() => onOpen(group.events)}
          >
            <span className="inline-flex h-5 min-w-5 items-center justify-center rounded-full bg-primary px-1 text-[11px] font-medium text-primary-foreground">
              {group.events.length > 1 ? (
                group.events.length
              ) : (
                <span className="block h-2.5 w-2.5 rounded-full bg-primary-foreground" aria-hidden="true" />
              )}
            </span>
          </button>
        );
      })}
      <span className="sr-only">
        {first} to {last}
      </span>
    </div>
  );
}

function MobileChartMenu() {
  const registry = useContext(ChartExportContext);
  const { axis, setAxis } = useModeControls();
  const { open, setOpen, rootRef } = useDismissableMenu();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const sync = () => setReady(registry?.current != null);
    sync();
    return registry?.subscribe(sync);
  }, [registry]);

  return (
    <div ref={rootRef} className="relative shrink-0">
      <button
        type="button"
        className="inline-flex h-9 w-9 items-center justify-center rounded-lg border bg-card text-lg leading-none hover:bg-muted"
        aria-label="Chart menu"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        ⋯
      </button>
      {open && (
        <div role="menu" aria-label="Chart menu" className="absolute right-0 top-full z-30 mt-1 min-w-44 rounded-lg border bg-card py-1 shadow-lg">
          <button
            type="button"
            role="menuitemradio"
            aria-label="Calendar"
            aria-checked={axis === "calendar"}
            className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm hover:bg-muted"
            onClick={() => {
              setAxis("calendar");
              setOpen(false);
            }}
          >
            <span className="w-3" aria-hidden="true">{axis === "calendar" ? "✓" : ""}</span>
            Calendar
          </button>
          <button
            type="button"
            role="menuitemradio"
            aria-label="Timeline"
            aria-checked={axis === "timeline"}
            className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm hover:bg-muted"
            onClick={() => {
              setAxis("timeline");
              setOpen(false);
            }}
          >
            <span className="w-3" aria-hidden="true">{axis === "timeline" ? "✓" : ""}</span>
            Timeline
          </button>
          {ready && registry && (
            <>
              <div className="my-1 border-t" />
              <button
                type="button"
                role="menuitem"
                className="block w-full px-3 py-1.5 text-left text-sm hover:bg-muted"
                onClick={() => {
                  setOpen(false);
                  registry.current?.downloadPng();
                }}
              >
                PNG
              </button>
              <button
                type="button"
                role="menuitem"
                className="block w-full px-3 py-1.5 text-left text-sm hover:bg-muted"
                onClick={() => {
                  setOpen(false);
                  registry.current?.downloadCsv();
                }}
              >
                CSV
              </button>
            </>
          )}
        </div>
      )}
    </div>
  );
}

function useDismissableMenu() {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) {
      return;
    }
    function onPointerDown(event: MouseEvent) {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);
  return { open, setOpen, rootRef };
}

function useModeControls() {
  const mode = useContext(ChartModeContext);
  const [eventFilter, setEventFilter] = useState<EventFilter>("off");
  const [axis, setAxis] = useState<ChartAxis>("calendar");
  return {
    eventFilter: mode?.eventFilter ?? eventFilter,
    setEventFilter: mode?.setEventFilter ?? setEventFilter,
    axis: mode?.axis ?? axis,
    setAxis: mode?.setAxis ?? setAxis,
  };
}

function Chevron() {
  return (
    <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" aria-hidden="true" fill="none">
      <path d="M4.5 6.5 8 10l3.5-3.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function CalendarAxisIcon() {
  return (
    <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" aria-hidden="true" fill="none">
      <path d="M4.5 2.5v1.5M8.5 2.5v1.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M3 5h7.5V8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M3 5v5.5h7.2" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M9.2 9.2 11.8 10.5 9.2 11.8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function TimelineAxisIcon() {
  return (
    <span aria-hidden="true" className="font-mono text-[10px] leading-none tracking-widest">
      1 2 3
    </span>
  );
}

function MenuTip({ id, text }: { id: string; text: string }) {
  return (
    <span
      id={id}
      role="tooltip"
      className="pointer-events-none absolute right-0 top-full z-20 mt-1 hidden whitespace-nowrap rounded-md border bg-card px-2 py-1 text-xs text-foreground shadow group-hover:block"
    >
      {text}
    </span>
  );
}

function EventFilterMenu() {
  const { eventFilter, setEventFilter } = useModeControls();
  const { open, setOpen, rootRef } = useDismissableMenu();
  const tipId = useId();
  const current = EVENT_MODES.find((item) => item[0] === eventFilter)!;
  return (
    <div ref={rootRef} className="group relative">
      <button
        type="button"
        className="inline-flex h-10 shrink-0 items-center gap-1 rounded-l-lg rounded-r-none border bg-card px-2 text-sm hover:bg-muted"
        aria-label={current[3]}
        aria-describedby={open ? undefined : tipId}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        {current[1]}
        <Chevron />
      </button>
      {!open && <MenuTip id={tipId} text={current[3]} />}
      {open && (
        <div role="menu" aria-label="Growth events" className="absolute right-0 top-full z-30 mt-1 min-w-40 rounded-lg border bg-card py-1 shadow-lg">
          {EVENT_MODES.map(([key, , label, tip]) => (
            <button
              key={key}
              type="button"
              role="menuitemradio"
              aria-label={tip}
              aria-checked={eventFilter === key}
              className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm hover:bg-muted"
              onClick={() => {
                setEventFilter(key);
                setOpen(false);
              }}
            >
              <span className="w-3" aria-hidden="true">{eventFilter === key ? "✓" : ""}</span>
              {label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function AxisMenu() {
  const { axis, setAxis } = useModeControls();
  const { open, setOpen, rootRef } = useDismissableMenu();
  const tipId = useId();
  const label = axis === "timeline" ? "Timeline" : "Calendar";
  const short = axis === "timeline" ? "Day" : "Cal";
  return (
    <div ref={rootRef} className="group relative -ml-px">
      <button
        type="button"
        className="inline-flex h-10 shrink-0 items-center gap-1 rounded-l-none rounded-r-lg border bg-card px-2 text-sm hover:bg-muted"
        aria-label={label}
        aria-describedby={open ? undefined : tipId}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        {short}
        <Chevron />
      </button>
      {!open && <MenuTip id={tipId} text={label} />}
      {open && (
        <div role="menu" aria-label="Chart axis" className="absolute right-0 top-full z-30 mt-1 min-w-40 rounded-lg border bg-card py-1 shadow-lg">
          <button
            type="button"
            role="menuitemradio"
            aria-label="Calendar"
            aria-checked={axis === "calendar"}
            className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm hover:bg-muted"
            onClick={() => {
              setAxis("calendar");
              setOpen(false);
            }}
          >
            <span className="w-3" aria-hidden="true">{axis === "calendar" ? "✓" : ""}</span>
            <CalendarAxisIcon />
            Calendar
          </button>
          <button
            type="button"
            role="menuitemradio"
            aria-label="Timeline"
            aria-checked={axis === "timeline"}
            className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm hover:bg-muted"
            onClick={() => {
              setAxis("timeline");
              setOpen(false);
            }}
          >
            <span className="w-3" aria-hidden="true">{axis === "timeline" ? "✓" : ""}</span>
            <TimelineAxisIcon />
            Timeline
          </button>
        </div>
      )}
    </div>
  );
}

export function ChartDownloadMenu() {
  const registry = useContext(ChartExportContext);
  const [ready, setReady] = useState(false);
  const { open, setOpen, rootRef } = useDismissableMenu();
  const tipId = useId();

  useEffect(() => {
    if (!registry) {
      return;
    }
    const sync = () => setReady(registry.current !== null);
    sync();
    return registry.subscribe(sync);
  }, [registry]);

  if (!registry || !ready) {
    return null;
  }

  function choose(kind: "png" | "csv") {
    setOpen(false);
    const actions = registry.current as ExportActions;
    if (kind === "png") {
      actions.downloadPng();
      return;
    }
    actions.downloadCsv();
  }

  return (
    <>
      <div className="inline-flex items-stretch">
        <EventFilterMenu />
        <AxisMenu />
      </div>
      <span className="w-px self-stretch bg-border" aria-hidden="true" />
      <div ref={rootRef} className="group relative">
        <button
          type="button"
          className="inline-flex h-10 shrink-0 items-center rounded-lg border bg-card p-1 hover:bg-muted"
          aria-label="Download"
          aria-describedby={open ? undefined : tipId}
          aria-haspopup="menu"
          aria-expanded={open}
          onClick={() => setOpen((value) => !value)}
        >
          <span className="inline-flex min-h-8 items-center gap-0.5 px-1.5 py-1.5">
            <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" aria-hidden="true" fill="none">
              <path
                d="M8 2.5v7.5M5 7.5 8 10.5 11 7.5M3 13h10"
                stroke="currentColor"
                strokeWidth="1.4"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
            <Chevron />
          </span>
        </button>
        {!open && <MenuTip id={tipId} text="Download" />}
        {open && (
          <div
            role="menu"
            aria-label="Download format"
            className="absolute right-0 top-full z-30 mt-1 min-w-36 rounded-lg border bg-card py-1 shadow-lg"
          >
            <button type="button" role="menuitem" className="block w-full px-3 py-1.5 text-left text-sm hover:bg-muted" onClick={() => choose("png")}>
              PNG
            </button>
            <button type="button" role="menuitem" className="block w-full px-3 py-1.5 text-left text-sm hover:bg-muted" onClick={() => choose("csv")}>
              CSV
            </button>
          </div>
        )}
      </div>
    </>
  );
}
