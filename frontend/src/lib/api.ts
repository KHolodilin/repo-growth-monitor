export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
    ...init,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: response.statusText }));
    throw new Error(body.message ?? "Request failed");
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}

export type Owner = {
  id: number;
  githubId: number;
  login: string;
  ownerType: string;
  avatarUrl?: string;
  htmlUrl?: string;
};

export type Repository = {
  id: number;
  githubId: number;
  name: string;
  fullName: string;
  description?: string;
  visibility: string;
  defaultBranch?: string;
  language?: string;
  fork: boolean;
  archived: boolean;
  stars: number;
  watchers: number;
  forks: number;
  openIssues: number;
  trackingEnabled: boolean;
  githubCreatedAt?: string;
  githubUpdatedAt?: string;
  githubUrl?: string;
  owner: Owner;
};

export type Dashboard = {
  period: string;
  from: string;
  to: string;
  lastSyncAt: string | null;
  state: "NO_REPOSITORIES" | "FIRST_COLLECTION" | "READY";
  partialData: { present: boolean; message: string } | null;
  collectionWarning: { partialRepositories: number; message: string } | null;
  activeCollection: { status: string; successfulJobs: number; plannedJobs: number } | null;
  summary: {
    repositories: number;
    views: { value: number; growthPercent: number | null };
    visitors: { value: number; growthPercent: number | null };
    clones: { value: number; growthPercent: number | null };
    stars: { total: number; change: number | null };
  };
  traffic: { date: string; views: number | null; visitors: number | null; clones: number | null }[];
  repositories: {
    id: number;
    fullName: string;
    visitors: number;
    views: number;
    clones: number;
    stars: number;
    growthPercent: number | null;
    collectionStatus: string | null;
    jobs: { jobType: string; status: string }[];
  }[];
};

export type TrafficPoint = {
  date: string;
  views: number | null;
  uniqueVisitors: number | null;
  clones: number | null;
  uniqueCloners: number | null;
};

export type CollectionJob = {
  jobType: string;
  status: string;
  errorMessage?: string;
  startedAt?: string;
  completedAt?: string;
};

export type CollectionRun = {
  id: number;
  repositoryId: number;
  businessDate: string;
  status: string;
  plannedJobs: number;
  successfulJobs: number;
  failedJobs: number;
  createdAt?: string;
  completedAt?: string;
  jobs: CollectionJob[];
};

export type RepositoryTraffic = {
  repository: Repository;
  owner: Owner;
  period: string;
  totals: { views: number; uniqueVisitors: number; clones: number; uniqueCloners: number };
  traffic: TrafficPoint[];
  referrers: { referrer: string; views: number; uniqueVisitors: number }[];
  paths: { path: string; title?: string; views: number; uniqueVisitors: number }[];
  lastCollection?: CollectionRun;
};

export type SearchQuery = {
  id: number;
  repositoryId: number;
  name: string;
  query: string;
  enabled: boolean;
  resultLimit: number;
};

export type SearchHistory = {
  query: SearchQuery;
  currentRank: number | null;
  change7d: number | null;
  change30d: number | null;
  bestRank: number | null;
  points: { date: string; position: number | null; searchRunId: number }[];
};

export type SearchRunResults = {
  run: {
    id: number;
    businessDate: string;
    totalCount: number | null;
    trackedRepositoryPosition: number | null;
    status: string;
  };
  query: SearchQuery;
  rows: {
    result: {
      position: number;
      githubRepositoryId: number;
      fullName: string;
      owner: string;
      stars: number;
      forks: number;
      language?: string;
      description?: string;
    };
    positionDelta: number | null;
  }[];
};
