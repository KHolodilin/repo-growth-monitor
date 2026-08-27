import { NavLink, Outlet, useLocation } from "react-router-dom";
import { BarChart3 } from "lucide-react";

export function Layout() {
  const location = useLocation();
  const dashboardActive = location.pathname === "/" || location.pathname === "/dashboard";

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b bg-card">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-4">
          <div className="flex items-center gap-2 font-semibold">
            <BarChart3 className="h-5 w-5 text-primary" />
            Repo Growth Monitor
          </div>
          <nav className="flex gap-4 text-sm">
            <NavLink to="/" className={() => (dashboardActive ? "font-medium text-primary" : "text-muted-foreground hover:text-foreground")} end>
              Dashboard
            </NavLink>
            <NavLink to="/repositories" className={linkClass}>
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

function linkClass({ isActive }: { isActive: boolean }) {
  return isActive ? "text-primary font-medium" : "text-muted-foreground hover:text-foreground";
}
