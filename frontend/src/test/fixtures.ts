import type {
  Dashboard,
  GrowthEvent,
  Repository,
  RepositoryTraffic,
  SearchHistory,
  SearchRunResults,
  SnapshotHistory,
  TopicHistory,
  TopicRunResults,
} from "../lib/api";

export const owner = {
  id: 1,
  githubId: 1,
  login: "KHolodilin",
  ownerType: "USER",
};

export const repository: Repository = {
  id: 13,
  githubId: 99,
  name: "spring-transactional-outbox-kafka",
  fullName: "KHolodilin/spring-transactional-outbox-kafka",
  description: "Transactional outbox",
  visibility: "PUBLIC",
  defaultBranch: "main",
  language: "Java",
  fork: false,
  archived: false,
  stars: 21,
  watchers: 0,
  forks: 16,
  openIssues: 1,
  trackingEnabled: true,
  githubUpdatedAt: "2026-09-23T00:00:00Z",
  lastCommitAt: "2026-09-11T19:00:00Z",
  githubUrl: "https://github.com/KHolodilin/spring-transactional-outbox-kafka",
  owner,
  contributors: 7,
  activityStatus: "ACTIVE",
  lastActivityAt: "2026-09-11T19:00:00Z",
  topics: ["kafka", "outbox"],
  health: {
    discoverability: [
      { label: "Description", passed: true },
      { label: "Topics", passed: false },
    ],
    communityStandards: [{ label: "README", passed: true }],
  },
};

export const traffic: RepositoryTraffic = {
  repository,
  owner,
  period: "30d",
  totals: { views: 10, uniqueVisitors: 4, clones: 2, uniqueCloners: 1 },
  traffic: [
    { date: "2026-09-22", views: 5, uniqueVisitors: 2, clones: 1, uniqueCloners: 1 },
    { date: "2026-09-23", views: 5, uniqueVisitors: 2, clones: 1, uniqueCloners: 0 },
  ],
  referrers: [{ referrer: "github.com", views: 8, uniqueVisitors: 3 }],
  referrerSnapshotAt: "2026-09-23T06:00:00Z",
  paths: [
    { path: "/KHolodilin/spring-transactional-outbox-kafka", views: 6, uniqueVisitors: 3, servicePath: false },
    { path: "/KHolodilin/spring-transactional-outbox-kafka/issues", views: 1, uniqueVisitors: 1, servicePath: true },
  ],
  pathSnapshotAt: "2026-09-23T06:00:00Z",
  lastCollection: {
    id: 1,
    repositoryId: 13,
    businessDate: "2026-09-23",
    status: "SUCCESS",
    plannedJobs: 2,
    successfulJobs: 2,
    failedJobs: 0,
    createdAt: "2026-09-23T06:00:00Z",
    completedAt: "2026-09-23T06:01:00Z",
    jobs: [
      { jobType: "TRAFFIC", status: "SUCCESS" },
      { jobType: "REFERRERS", status: "SUCCESS" },
    ],
  },
};

export const searchHistory: SearchHistory = {
  query: {
    id: 5,
    repositoryId: 13,
    name: "transactional outbox language:java",
    query: "transactional outbox language:java",
    enabled: true,
    resultLimit: 50,
  },
  currentRank: 4,
  change: { kind: "IMPROVED", amount: 2, rank: 4 },
  change7d: 1,
  change30d: 3,
  bestRank: 2,
  points: [{ date: "2026-09-23", position: 4, searchRunId: 9 }],
  lastChecked: "2026-09-23T06:10:00Z",
  searchStatus: "SUCCESS",
  totalResults: 80,
  missedDates: [],
};

export const topicHistory: TopicHistory = {
  watch: {
    id: 7,
    repositoryId: 13,
    topic: "outbox",
    language: null,
    sort: "stars",
    sortOrder: "desc",
    enabled: true,
    resultLimit: 50,
  },
  currentRank: 24,
  change: { kind: "NONE", amount: 0, rank: 24 },
  change7d: null,
  change30d: null,
  bestRank: 24,
  points: [{ date: "2026-09-23", position: 24, topicRunId: 1 }],
  lastChecked: "2026-09-23T06:10:00Z",
  searchStatus: "SUCCESS",
  totalResults: 170,
  missedDates: [],
};

export const growthEvent: GrowthEvent = {
  id: 1,
  repositoryId: 13,
  eventAt: "2026-09-22T12:00:00Z",
  category: "PROMOTION",
  type: "LINKEDIN_POST",
  title: "LinkedIn launch",
  source: "MANUAL",
};

export const dashboardReady: Dashboard = {
  period: "30d",
  from: "2026-08-24",
  to: "2026-09-23",
  lastSyncAt: "2026-09-23T06:00:00Z",
  state: "READY",
  partialData: { present: true, message: "Some days are missing." },
  collectionWarning: { partialRepositories: 1, message: "1 repository collected partially." },
  activeCollection: null,
  summary: {
    repositories: 1,
    views: { value: 10, growthPercent: 12.5 },
    visitors: { value: 4, growthPercent: -2 },
    clones: { value: 2, growthPercent: null },
    stars: { total: 21, change: 3 },
  },
  traffic: [{ date: "2026-09-23", views: 5, visitors: 2, clones: 1 }],
  repositories: [
    {
      id: 13,
      fullName: repository.fullName,
      visitors: 4,
      views: 10,
      clones: 2,
      stars: 21,
      growthPercent: 12.5,
      collectionStatus: "PARTIAL",
      jobs: [
        { jobType: "TRAFFIC", status: "SUCCESS" },
        { jobType: "REFERRERS", status: "FAILED" },
      ],
      archived: false,
      activityAt: "2026-09-11T19:00:00Z",
      activityStatus: "ACTIVE",
    },
  ],
};

export const snapshotHistory: SnapshotHistory = {
  repositoryId: 13,
  kind: "REFERRERS",
  days: 14,
  from: "2026-09-10",
  to: "2026-09-23",
  dates: ["2026-09-22", "2026-09-23"],
  rows: [
    {
      key: "github.com",
      title: "github.com",
      cells: [
        {
          date: "2026-09-22",
          visitors: 2,
          views: 4,
          visitorsDelta: 1,
          viewsDelta: 2,
          firstSeen: false,
        },
        {
          date: "2026-09-23",
          visitors: 3,
          views: 8,
          visitorsDelta: 1,
          viewsDelta: 4,
          firstSeen: false,
        },
      ],
    },
  ],
};

export const searchResults: SearchRunResults = {
  run: {
    id: 9,
    businessDate: "2026-09-23",
    totalCount: 80,
    trackedRepositoryPosition: 4,
    status: "SUCCESS",
    enrichmentStatus: "SUCCESS",
    completedAt: "2026-09-23T06:10:00Z",
  },
  query: searchHistory.query,
  rows: [
    {
      result: {
        position: 1,
        githubRepositoryId: 1,
        fullName: "other/repo",
        owner: "other",
        stars: 100,
        watchers: 1,
        forks: 2,
        contributors: 3,
        language: "Java",
        htmlUrl: "https://github.com/other/repo",
        activityStatus: "ACTIVE",
        activityAt: "2026-09-20T00:00:00Z",
      },
      positionDelta: -1,
    },
    {
      result: {
        position: 4,
        githubRepositoryId: 99,
        fullName: repository.fullName,
        owner: "KHolodilin",
        stars: 21,
        watchers: 0,
        forks: 16,
        contributors: 7,
        language: "Java",
        htmlUrl: repository.githubUrl,
        activityStatus: "ACTIVE",
      },
      positionDelta: 2,
    },
  ],
};

export const topicResults: TopicRunResults = {
  run: {
    id: 1,
    businessDate: "2026-09-23",
    totalCount: 170,
    trackedRepositoryPosition: 24,
    status: "SUCCESS",
    completedAt: "2026-09-23T06:10:00Z",
  },
  watch: topicHistory.watch,
  rows: [
    {
      result: {
        position: 1,
        githubRepositoryId: 2,
        fullName: "big/outbox",
        owner: "big",
        stars: 900,
        watchers: 10,
        forks: 40,
        language: "Java",
        htmlUrl: "https://github.com/big/outbox",
      },
      positionDelta: null,
    },
    {
      result: {
        position: 24,
        githubRepositoryId: 99,
        fullName: repository.fullName,
        owner: "KHolodilin",
        stars: 21,
        watchers: 0,
        forks: 16,
        language: "Java",
        htmlUrl: repository.githubUrl,
      },
      positionDelta: 0,
    },
  ],
};
