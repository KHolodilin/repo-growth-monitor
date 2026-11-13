import { api, type GrowthEvent, type Dashboard } from "./api";
import type { DashboardViewData } from "../pages/DashboardPage";
export type PublicOverview = { owner: { id: string; login: string; type: string }; repository?: { id: string; name: string; description?: string; pathPrefixes?: string[] }; canonicalPath: string; repositoryCount: number; modules: { traffic: boolean; referrers: boolean; paths: boolean; search: boolean; topics: boolean }; freshness: { state: string; stale?: boolean; lastSuccessfulSnapshotAt?: string } };
export type PublicRow = {
  id: string;
  name: string;
  description?: string;
  stars?: number | null;
  forks?: number | null;
  views?: number | null;
  visitors?: number | null;
  clones?: number | null;
  growthPercent?: number | null;
  archived?: boolean;
  lastObservedAt?: string;
  activityAt?: string | null;
  activityStatus?: "ACTIVE" | "LOW_ACTIVITY" | "INACTIVE" | "ARCHIVED" | "UNKNOWN" | null;
  discoverabilityPassed?: number | null;
  discoverabilityTotal?: number | null;
};
export type PublicPage<T> = { items: T[]; nextCursor?: string | null; nextDate?: string | null };
export type PublicMetric = { value: number | null; absoluteChange: number | null; growthPercent: number | null; complete: boolean; observedValue?: number; observedRepositories?: number; expectedRepositories?: number };
export type StatsResponse = { period: string; from: string; to: string; repositories: number; highlights?: Dashboard["highlights"]; totals: Record<"stars" | "forks" | "watchers" | "contributors", PublicMetric> };
export type TrafficResponse = {
  totals: Record<"views" | "visitors" | "clones" | "uniqueCloners", PublicMetric>;
  coverage?: { observedRepositoryDays: number; expectedRepositoryDays: number };
  previousCoverage?: { observedRepositoryDays: number; expectedRepositoryDays: number };
};
export type HistoryRow = { date: string; stars?: number | null; forks?: number | null; watchers?: number | null; contributors?: number | null; views?: number | null; visitors?: number | null; clones?: number | null; uniqueCloners?: number | null; missingRepositories?: number; complete?: boolean };
export function publicBase(owner: string, repository?: string) { return `/api/v1/public/${repository ? `repositories/${encodeURIComponent(owner)}/${encodeURIComponent(repository)}` : `owners/${encodeURIComponent(owner)}`}`; }
export function publicScope(overview: PublicOverview) { return overview.repository ? `public:repository:${overview.repository.id}` : `public:owner:${overview.owner.id}`; }
export async function historyRows(url: string, signal: AbortSignal): Promise<HistoryRow[]> { const rows: HistoryRow[] = []; let before: string | undefined; for (let page=0;page<20;page++) { const response = await api<PublicPage<HistoryRow>>(`${url}&limit=100${before ? `&before=${encodeURIComponent(before)}` : ""}`, { signal }); rows.push(...response.items); if (!response.nextDate) return rows.sort((a,b)=>a.date.localeCompare(b.date)); before=response.nextDate; } throw new Error("This archive is too large for one chart. Choose a shorter period."); }
export function adaptPublicDashboard(overview: PublicOverview, stats: StatsResponse, statsRows: HistoryRow[], traffic: TrafficResponse | null, trafficRows: HistoryRow[], repositories: PublicRow[]): DashboardViewData {
 const unknown = { value: null, growthPercent: null };
 // Incomplete public totals keep value=null for honesty, but KPIs must still show observedValue
 // (otherwise Partial data cards render as "—" while observedValue is already known).
 const trafficMetric=(name:"views"|"visitors"|"clones"|"uniqueCloners")=>{
  if(!traffic)return unknown;
  const metric=traffic.totals[name];
  return {
   value: metric.value ?? metric.observedValue ?? null,
   growthPercent: metric.growthPercent,
   absoluteChange: metric.absoluteChange,
   coverage: traffic.coverage
    ? { observedDays: traffic.coverage.observedRepositoryDays, expectedDays: traffic.coverage.expectedRepositoryDays }
    : null,
   previousCoverage: traffic.previousCoverage
    ? { observedDays: traffic.previousCoverage.observedRepositoryDays, expectedDays: traffic.previousCoverage.expectedRepositoryDays }
    : null,
  };
 };
 return { period: stats.period, from: stats.from, to: stats.to, state: "READY", lastSyncAt: overview.freshness.lastSuccessfulSnapshotAt ?? null, partialData: null, collectionWarning: null, activeCollection: null,
 summary: { clones:trafficMetric("clones"), repositories: stats.repositories, stars: { total: stats.totals.stars.value ?? 0, change: stats.totals.stars.absoluteChange }, views: trafficMetric("views"), visitors: trafficMetric("visitors"), uniqueCloners: trafficMetric("uniqueCloners") },
 traffic: trafficRows.map(row=>({ date:row.date, views:row.views ?? null, visitors:row.visitors ?? null, clones:row.clones ?? null, uniqueCloners:row.uniqueCloners ?? null, missingRepositories: row.missingRepositories })),
 stats: { totals: stats.totals, points: statsRows.map(row=>({ date:row.date, stars:row.stars ?? null, forks:row.forks ?? null, watchers:row.watchers ?? null, contributors:row.contributors ?? null, missingRepositories: row.missingRepositories })) },
 repositories: repositories.map(row=>({ id:row.id, fullName:row.name, visitors:row.visitors ?? null, views:row.views ?? null, clones:row.clones ?? null, stars:row.stars ?? null, forks:row.forks ?? null, growthPercent:row.growthPercent??null, collectionStatus:null, jobs:[], archived:row.archived ?? false, activityAt:row.activityAt ?? row.lastObservedAt ?? null, activityStatus:row.activityStatus ?? "UNKNOWN", discoverabilityPassed:row.discoverabilityPassed ?? null, discoverabilityTotal:row.discoverabilityTotal ?? null })), highlights:stats.highlights??null };
}
export function relativeRepositoryPath(raw: string, prefixes: string[]) { for (const prefix of [...prefixes].sort((a,b)=>b.length-a.length)) { const end=raw.charAt(prefix.length); if (raw.slice(0,prefix.length).toLowerCase()===prefix.toLowerCase() && (!end || "/?#".includes(end))) { const tail=raw.slice(prefix.length); return !tail ? "/" : tail.startsWith("/") ? tail : `/${tail}`; } } return raw; }
export async function publicEvents(base: string, signal: AbortSignal): Promise<GrowthEvent[]> { const page=await api<PublicPage<{ id:string; repositoryId?:string; eventAt:string; title:string; type:string; category:string; source:string }>>(`${base}/growth-events?limit=100`,{signal}); return page.items.map(event=>({ ...event, repositoryId:event.repositoryId ?? "" })); }

export async function publicRepositoryRows(base:string,period:string,signal:AbortSignal):Promise<PublicRow[]> {
 const rows:PublicRow[]=[];let after:string|undefined;
 for(let index=0;index<20;index++){
  const page=await api<PublicPage<PublicRow>>(`${base}/repositories?limit=100&period=${period}${after?`&after=${encodeURIComponent(after)}`:""}`,{signal});
  rows.push(...page.items);if(!page.nextCursor)return rows;after=page.nextCursor;
 }
 throw new Error("This owner has too many repositories for one dashboard. Open an individual repository.");
}
