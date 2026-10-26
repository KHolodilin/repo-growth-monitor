import { createContext, useCallback, useContext, useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, type GrowthEvent } from "../lib/api";
import { readChartAxis, readChartEventFilter, subscribeChartMode, writeChartAxis, writeChartEventFilter } from "../lib/chartModePrefs";
import { asGrowthEvents, filterGrowthEvents, type EventFilter } from "../lib/growthEvents";
import type { ChartAxis } from "../lib/chartPresentation";
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

type ChartMode = {
  eventFilter: EventFilter;
  setEventFilter: (filter: EventFilter) => void;
  axis: ChartAxis;
  setAxis: (axis: ChartAxis) => void;
  events: GrowthEvent[];
  period?: string;
  openMarker: (events: GrowthEvent[]) => void;
};

const ChartExportContext = createContext<ExportRegistry | null>(null);
const ChartModeContext = createContext<ChartMode | null>(null);

const EVENT_MODES = [
  ["all", "All", "All events"],
  ["github", "GitHub", "GitHub events"],
  ["promotion", "Promotion", "Promotion events"],
  ["off", "Off", "Events off"],
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
    eventFilter: mode?.eventFilter ?? ("all" as const),
    axis: mode?.axis ?? ("calendar" as const),
    events: mode?.events ?? [],
    period: mode?.period,
    openMarker: mode?.openMarker ?? (() => undefined),
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
  children,
  events: eventsProp,
  repositoryId,
  period = "30d",
  onEventsChanged,
}: {
  title: string;
  controls?: ReactNode;
  children: ReactNode;
  events?: GrowthEvent[];
  repositoryId?: number;
  period?: string;
  onEventsChanged?: () => void;
}) {
  const [loaded, setLoaded] = useState<GrowthEvent[]>([]);
  const [reloadKey, setReloadKey] = useState(0);
  const [eventFilter, setEventFilterState] = useState(readChartEventFilter);
  const [axis, setAxisState] = useState(readChartAxis);
  const [marker, setMarker] = useState<GrowthEvent[] | null>(null);
  useEffect(() => subscribeChartMode(() => {
    setEventFilterState(readChartEventFilter());
    setAxisState(readChartAxis());
  }), []);
  const setEventFilter = useCallback((value: EventFilter) => {
    writeChartEventFilter(value);
  }, []);
  const setAxis = useCallback((value: ChartAxis) => {
    writeChartAxis(value);
  }, []);

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
    () => ({ eventFilter, setEventFilter, axis, setAxis, events, period, openMarker }),
    [eventFilter, setEventFilter, axis, setAxis, events, period, openMarker],
  );

  return (
    <ChartExportProvider>
      <ChartModeContext.Provider value={mode}>
        <Card>
          <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
            <h2 className="font-medium">{title}</h2>
            <div className="flex flex-nowrap items-center justify-end gap-2">
              {controls}
              <ChartDownloadMenu />
            </div>
          </div>
          {children}
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
  const [eventFilter, setEventFilter] = useState<EventFilter>("all");
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
        className="inline-flex h-10 shrink-0 items-center gap-1 rounded-lg border bg-card px-2 text-sm hover:bg-muted"
        aria-label={current[2]}
        aria-describedby={open ? undefined : tipId}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        {current[1]}
        <Chevron />
      </button>
      {!open && <MenuTip id={tipId} text={current[2]} />}
      {open && (
        <div role="menu" aria-label="Growth events" className="absolute right-0 top-full z-30 mt-1 min-w-40 rounded-lg border bg-card py-1 shadow-lg">
          {EVENT_MODES.map(([key, label, tip]) => (
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
  return (
    <div ref={rootRef} className="group relative">
      <button
        type="button"
        className="inline-flex h-10 shrink-0 items-center rounded-lg border bg-card p-1 hover:bg-muted"
        aria-label={label}
        aria-describedby={open ? undefined : tipId}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <span className="inline-flex min-h-8 items-center gap-0.5 px-1.5 py-1.5">
          <CalendarAxisIcon />
          <Chevron />
        </span>
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
            <span aria-hidden="true" className="font-mono text-[10px] tracking-widest">1  2  3</span>
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
      <EventFilterMenu />
      <span className="w-px self-stretch bg-border" aria-hidden="true" />
      <AxisMenu />
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
