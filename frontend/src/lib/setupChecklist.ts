import { collectionFailureReason, JOB_LABELS, type ProgressJob } from "./collectProgress";
import { TRAFFIC_PERMISSION, type PlannerWindow } from "./githubAccess";

export const DEFAULT_PLANNER: PlannerWindow = {
  timezone: "UTC",
  from: "10:00",
  to: "18:00",
  open: false,
};

export const COLLECTION_JOB_TYPES = [
  "TRAFFIC",
  "REFERRERS",
  "POPULAR_PATHS",
  "REPOSITORY_STATS",
  "GROWTH_EVENTS",
] as const;

export type JobLine = {
  jobType: string;
  label: string;
  mark: string;
  detail: string | null;
};

export function autoCollectWindow(planner: PlannerWindow): string {
  const zone = planner.timezone === "UTC" ? "UTC" : planner.timezone;
  return `Auto-collect runs every day from ${planner.from} to ${planner.to} ${zone}.`;
}

export function checklistSchedule(planner: PlannerWindow): string {
  return `${autoCollectWindow(planner)} The first collection starts as soon as a repository is enabled.`;
}

export function headerSchedule(planner: PlannerWindow): string {
  const windowText = autoCollectWindow(planner);
  if (planner.open) {
    return windowText;
  }
  return `${windowText} It has not started yet today.`;
}

export function collectionJobLines(jobs: ProgressJob[] | null | undefined, trafficPermissionMissing: boolean): JobLine[] {
  const byType = new Map((jobs ?? []).map((job) => [job.jobType, job]));
  return COLLECTION_JOB_TYPES.map((jobType) => jobLine(jobType, byType.get(jobType), trafficPermissionMissing));
}

function jobLine(jobType: string, job: ProgressJob | undefined, trafficPermissionMissing: boolean): JobLine {
  const label = JOB_LABELS[jobType];
  if (jobType === "TRAFFIC" && trafficPermissionMissing) {
    return { jobType, label, mark: "✕", detail: TRAFFIC_PERMISSION };
  }
  if (job?.status === "SUCCESS") {
    return { jobType, label, mark: "✓", detail: null };
  }
  if (job?.status === "FAILED") {
    return { jobType, label, mark: "✕", detail: collectionFailureReason(job.errorMessage, job.jobType) };
  }
  if (job?.status === "RUNNING" || job?.status === "RETRY") {
    return { jobType, label, mark: "…", detail: null };
  }
  return { jobType, label, mark: "○", detail: null };
}
