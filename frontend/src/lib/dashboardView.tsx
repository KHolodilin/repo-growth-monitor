import { createContext, useContext, type ReactNode } from "react";
import { repoPathFromFullName } from "./repoPaths";
export type DashboardViewOptions = { public?: boolean; scopeKey?: string; heading?: string; headerLeading?: ReactNode; repositoryHref?: (id: number | string) => string; showRepositories?: boolean; trafficEnabled?: boolean; statsEnabled?: boolean };
export const DashboardViewContext = createContext<DashboardViewOptions>({});
export const useDashboardView = () => useContext(DashboardViewContext);
export function repositoryHref(view: DashboardViewOptions, id: number | string, fullName?: string | null) {
  if (view.repositoryHref) {
    return view.repositoryHref(id);
  }
  if (fullName) {
    return repoPathFromFullName(fullName);
  }
  return "/";
}
