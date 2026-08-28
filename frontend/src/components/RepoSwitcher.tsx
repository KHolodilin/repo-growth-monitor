import { useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import { Book, Check, ChevronDown, Lock, Search } from "lucide-react";
import { api, type Repository } from "../lib/api";
import { cn } from "../lib/utils";

type RepoSwitcherProps = {
  currentId: number;
  currentLabel: string;
  hrefFor: (repositoryId: number) => string;
};

export function RepoSwitcher({ currentId, currentLabel, hrefFor }: RepoSwitcherProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const rootRef = useRef<HTMLSpanElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const highlightedRef = useRef<HTMLButtonElement | null>(null);
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [repos, setRepos] = useState<Repository[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [highlighted, setHighlighted] = useState(0);

  const options = useMemo(() => {
    const tracked = (repos ?? [])
      .filter((repo) => repo.trackingEnabled || repo.id === currentId)
      .sort((left, right) => left.name.localeCompare(right.name, undefined, { sensitivity: "base" }));
    const needle = query.trim().toLowerCase();
    if (!needle) {
      return tracked;
    }
    return tracked.filter(
      (repo) => repo.name.toLowerCase().includes(needle) || repo.fullName.toLowerCase().includes(needle),
    );
  }, [repos, currentId, query]);

  useEffect(() => {
    if (!open) {
      return;
    }
    searchRef.current?.focus();
    function onPointerDown(event: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    function onKeyDown(event: globalThis.KeyboardEvent) {
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

  useEffect(() => {
    if (!open) {
      return;
    }
    if (query.trim()) {
      setHighlighted(0);
      return;
    }
    const index = options.findIndex((repo) => repo.id === currentId);
    setHighlighted(index >= 0 ? index : 0);
  }, [query, open, options, currentId]);

  useEffect(() => {
    highlightedRef.current?.scrollIntoView({ block: "nearest" });
  }, [highlighted]);

  async function openMenu() {
    setOpen(true);
    setQuery("");
    setError(null);
    if (repos) {
      return;
    }
    try {
      setRepos(await api<Repository[]>("/api/v1/repositories?refresh=false"));
    } catch (err) {
      setError((err as Error).message);
    }
  }

  function select(repo: Repository) {
    setOpen(false);
    const to = hrefFor(repo.id);
    if (to === `${location.pathname}${location.search}`) {
      return;
    }
    navigate(to);
  }

  function onSearchKeyDown(event: ReactKeyboardEvent<HTMLInputElement>) {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setHighlighted((index) => Math.min(index + 1, Math.max(options.length - 1, 0)));
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setHighlighted((index) => Math.max(index - 1, 0));
    } else if (event.key === "Enter") {
      event.preventDefault();
      const repo = options[highlighted];
      if (repo) {
        select(repo);
      }
    }
  }

  return (
    <span ref={rootRef} className="relative inline-block max-w-full align-bottom">
      <button
        type="button"
        className="inline-flex max-w-full items-center gap-1 font-medium text-muted-foreground hover:text-foreground"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label="Switch repository"
        onClick={() => {
          if (open) {
            setOpen(false);
            return;
          }
          void openMenu();
        }}
      >
        <span className="break-all text-left">{currentLabel}</span>
        <ChevronDown className="h-3.5 w-3.5 shrink-0 opacity-70" aria-hidden="true" />
      </button>
      {open && (
        <div className="absolute left-0 z-50 mt-1 w-[min(22rem,calc(100vw-2.5rem))] overflow-hidden rounded-xl border bg-card shadow-lg">
          <div className="border-b px-3 py-2 text-sm font-medium">Switch repository</div>
          <div className="relative border-b px-2 py-2">
            <Search className="pointer-events-none absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              ref={searchRef}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={onSearchKeyDown}
              placeholder="Search repositories"
              className="w-full rounded-md border bg-background py-1.5 pl-8 pr-3 text-sm outline-none focus:border-primary"
            />
          </div>
          <div className="max-h-72 overflow-y-auto py-1" role="listbox">
            {error && <p className="px-3 py-2 text-sm text-red-600">{error}</p>}
            {!error && repos === null && <p className="px-3 py-2 text-sm text-muted-foreground">Loading...</p>}
            {!error && repos !== null && options.length === 0 && (
              <p className="px-3 py-2 text-sm text-muted-foreground">No repositories found</p>
            )}
            {options.map((repo, index) => {
              const current = repo.id === currentId;
              const privateRepo = repo.visibility.toLowerCase() === "private";
              return (
                <button
                  key={repo.id}
                  ref={index === highlighted ? highlightedRef : undefined}
                  type="button"
                  role="option"
                  aria-selected={current}
                  className={cn(
                    "flex w-full items-center gap-2 border-l-2 px-3 py-1.5 text-left text-sm",
                    index === highlighted ? "border-primary bg-muted" : "border-transparent",
                  )}
                  onMouseEnter={() => setHighlighted(index)}
                  onClick={() => select(repo)}
                >
                  <span className="flex w-4 shrink-0 justify-center">
                    {current ? <Check className="h-3.5 w-3.5" aria-hidden="true" /> : null}
                  </span>
                  {privateRepo ? (
                    <Lock className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                  ) : (
                    <Book className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                  )}
                  <span className="min-w-0 truncate">{repo.name}</span>
                </button>
              );
            })}
          </div>
        </div>
      )}
    </span>
  );
}
