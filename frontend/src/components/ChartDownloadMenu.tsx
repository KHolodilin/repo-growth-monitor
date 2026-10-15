import { createContext, useContext, useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { Card } from "./ui";

type ExportActions = {
  downloadPng: () => void;
  downloadCsv: () => void;
};

type ExportRegistry = {
  current: ExportActions | null;
  subscribe: (listener: () => void) => () => void;
  publish: (next: ExportActions | null) => void;
};

const ChartExportContext = createContext<ExportRegistry | null>(null);

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
}: {
  title: string;
  controls?: ReactNode;
  children: ReactNode;
}) {
  return (
    <ChartExportProvider>
      <Card>
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
          <h2 className="font-medium">{title}</h2>
          <div className="flex flex-wrap items-center justify-end gap-2">
            {controls}
            <ChartDownloadMenu />
          </div>
        </div>
        {children}
      </Card>
    </ChartExportProvider>
  );
}

export function ChartDownloadMenu() {
  const registry = useContext(ChartExportContext);
  const [ready, setReady] = useState(false);
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const tipId = useId();

  useEffect(() => {
    if (!registry) {
      return;
    }
    const sync = () => setReady(registry.current !== null);
    sync();
    return registry.subscribe(sync);
  }, [registry]);

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
    <div ref={rootRef} className="group relative">
      <button
        type="button"
        className="inline-flex items-center gap-0.5 rounded-md border bg-card px-1.5 py-1 hover:bg-muted"
        aria-label="Download"
        aria-describedby={open ? undefined : tipId}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" aria-hidden="true" fill="none">
          <path
            d="M8 2.5v7.5M5 7.5 8 10.5 11 7.5M3 13h10"
            stroke="currentColor"
            strokeWidth="1.4"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
        <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" aria-hidden="true" fill="none">
          <path d="M4.5 6.5 8 10l3.5-3.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
      {!open && (
        <span
          id={tipId}
          role="tooltip"
          className="pointer-events-none absolute right-0 top-full z-20 mt-1 hidden whitespace-nowrap rounded-md border bg-card px-2 py-1 text-xs text-foreground shadow group-hover:block"
        >
          Download
        </span>
      )}
      {open && (
        <div
          role="menu"
          aria-label="Download format"
          className="absolute right-0 top-full z-30 mt-1 min-w-36 rounded-lg border bg-card py-1 shadow-lg"
        >
          <button
            type="button"
            role="menuitem"
            className="block w-full px-3 py-1.5 text-left text-sm hover:bg-muted"
            onClick={() => choose("png")}
          >
            PNG
          </button>
          <button
            type="button"
            role="menuitem"
            className="block w-full px-3 py-1.5 text-left text-sm hover:bg-muted"
            onClick={() => choose("csv")}
          >
            CSV
          </button>
        </div>
      )}
    </div>
  );
}
