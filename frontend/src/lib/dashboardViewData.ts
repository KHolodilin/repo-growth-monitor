import type { Dashboard as ApiDashboard } from "./api";

/**
 * Dashboard shape used by authenticated and public presentations.
 * Lives in lib so adapters and UI surfaces can share it without depending on pages.
 */
export type DashboardViewData = Omit<ApiDashboard, "repositories" | "summary"> & {
  summary: ApiDashboard["summary"] & { clones?: ApiDashboard["summary"]["views"] };
  repositories: (Omit<ApiDashboard["repositories"][number], "id" | "stars" | "forks"> & {
    id: number | string;
    stars: number | null;
    forks: number | null;
  })[];
};
