import { createContext, useContext } from "react";
export type DashboardViewOptions = { public?: boolean; scopeKey?: string; heading?: string; repositoryHref?: (id: number | string) => string; showRepositories?: boolean; trafficEnabled?: boolean; statsEnabled?: boolean };
export const DashboardViewContext = createContext<DashboardViewOptions>({});
export const useDashboardView = () => useContext(DashboardViewContext);
export function repositoryHref(view: DashboardViewOptions, id: number | string) { return view.repositoryHref?.(id) ?? `/admin/repositories/${id}`; }
