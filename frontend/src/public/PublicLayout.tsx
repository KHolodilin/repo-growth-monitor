import { useState } from "react";
import { Link, Outlet, useLocation } from "react-router-dom";
import { LogoMark, SignInIcon } from "../components/icons";

const PROJECT_GITHUB = "https://github.com/KHolodilin/repo-growth-monitor";

export function PublicLayout() {
  const location = useLocation();
  const [aboutOpen, setAboutOpen] = useState(false);

  return (
    <div className="flex min-h-screen flex-col bg-background text-foreground">
      <header className="border-b bg-card">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-4">
          <Link to="/" className="flex min-w-0 items-center gap-2 text-sm font-semibold" aria-label="Repo Growth Monitor home">
            <LogoMark className="h-7 w-7 shrink-0" />
            Repo Growth Monitor
          </Link>
          <a href="/login" aria-label="Sign in" className="inline-flex min-h-11 min-w-11 items-center justify-center rounded-full border">
            <SignInIcon className="h-6 w-6" />
          </a>
        </div>
      </header>
      <main className="mx-auto w-full max-w-6xl flex-1 p-4 sm:p-6">
        <Outlet />
      </main>
      <footer className="mt-auto border-t bg-card">
        <div className="mx-auto max-w-6xl space-y-2 px-4 py-4 text-xs text-muted-foreground md:px-6">
          {location.pathname === "/" ? (
            <p>Public dashboards use saved snapshots. Sign in only for repository management.</p>
          ) : null}
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <button
              type="button"
              className="font-medium text-primary hover:underline"
              aria-expanded={aboutOpen}
              onClick={() => setAboutOpen((value) => !value)}
            >
              About data
            </button>
            <span aria-hidden="true">·</span>
            <a className="font-medium text-primary hover:underline" href={PROJECT_GITHUB} target="_blank" rel="noreferrer">
              GitHub
            </a>
          </div>
          {aboutOpen && (
            <p>
              Data is collected once per day. Referrers and paths show the latest GitHub snapshot; History has the change
              between snapshots.
            </p>
          )}
        </div>
      </footer>
    </div>
  );
}

export function PublicNotFound() {
  return (
    <section role="alert" className="space-y-3 py-16">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <p>This page is unavailable.</p>
      <Link to="/" className="text-primary underline">
        Home
      </Link>
    </section>
  );
}
