import { NavLink, Outlet, useLocation } from "react-router-dom";
import { DashboardIcon, LogoMark, RepositoriesIcon } from "./icons";
import { cn } from "../lib/utils";

export function Layout() {
  const location = useLocation();
  const dashboardActive = location.pathname === "/" || location.pathname === "/dashboard";

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b bg-card">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-3.5">
          <NavLink to="/" className="flex items-center gap-2.5 text-slate-900" aria-label="Repo Growth Monitor">
            <LogoMark className="h-8 w-8 shrink-0 object-contain" />
            <span className="flex items-baseline gap-1 text-[15px] leading-none tracking-tight">
              <span className="font-bold">Repo Growth</span>
              <span className="font-normal">Monitor</span>
            </span>
          </NavLink>
          <nav className="flex items-center gap-6 text-sm">
            <NavLink
              to="/"
              end
              className={cn(
                "flex items-center gap-2",
                dashboardActive ? "font-medium text-primary" : "text-muted-foreground hover:text-foreground",
              )}
            >
              <DashboardIcon className="h-6 w-6" />
              Dashboard
            </NavLink>
            <NavLink to="/repositories" className={navClass}>
              <RepositoriesIcon className="h-6 w-6" />
              Repositories
            </NavLink>
          </nav>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-6 py-8">
        <Outlet />
      </main>
    </div>
  );
}

function navClass({ isActive }: { isActive: boolean }) {
  return cn(
    "flex items-center gap-2",
    isActive ? "font-medium text-primary" : "text-muted-foreground hover:text-foreground",
  );
}
