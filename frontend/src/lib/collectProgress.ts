export const JOB_LABELS: Record<string, string> = {
  TRAFFIC: "Traffic",
  REFERRERS: "Referrers",
  POPULAR_PATHS: "Popular Paths",
  REPOSITORY_STATS: "Repository Stats",
  GROWTH_EVENTS: "Growth Events",
};

export type ProgressJob = {
  jobType: string;
  status: string;
  errorMessage?: string | null;
};

export type ProgressRow = {
  id: number;
  fullName: string;
  collectionStatus: string | null;
  jobs: ProgressJob[];
};

export function collectionInProgress(status: string | null | undefined): boolean {
  return status === "RUNNING" || status === "PLANNED";
}

export function collectButtonLabel(trackedCount: number | null, repositoryScope: boolean): string {
  if (repositoryScope) {
    return "Collect this repository";
  }
  if (trackedCount === 0) {
    return "Select repositories first";
  }
  if (trackedCount == null) {
    return "Collect repositories";
  }
  return `Collect ${trackedCount} ${trackedCount === 1 ? "repository" : "repositories"}`;
}

export function headerProgress(options: {
  repositoryId: number | null;
  rows: ProgressRow[];
  watching: boolean;
}): string | null {
  if (options.repositoryId != null) {
    return repositoryLine(
      options.rows.find((row) => row.id === options.repositoryId),
      options.watching,
    );
  }
  return portfolioLine(options.rows, options.watching);
}

function repositoryLine(row: ProgressRow | undefined, watching: boolean): string | null {
  if (!row) {
    return watching ? "Collecting this repository" : null;
  }
  const active = collectionInProgress(row.collectionStatus);
  if (watching && !active) {
    return `Collecting ${row.fullName}`;
  }
  const phrases = jobPhrases(row.jobs);
  if (active) {
    return phrases.length > 0 ? `Collecting ${row.fullName}: ${phrases.map((phrase) => phrase.text).join(", ")}` : `Collecting ${row.fullName}`;
  }
  const failed = phrases.filter((phrase) => phrase.failed);
  if (failed.length === 0) {
    return null;
  }
  return failed.map((phrase) => phrase.text).join(", ");
}

function portfolioLine(rows: ProgressRow[], watching: boolean): string | null {
  if (rows.length === 0) {
    return null;
  }
  const active = rows.some((row) => collectionInProgress(row.collectionStatus));
  if (!watching && !active) {
    return null;
  }
  const done = active
    ? rows.filter((row) => row.collectionStatus != null && !collectionInProgress(row.collectionStatus)).length
    : 0;
  const base = `Collected ${done} of ${rows.length}`;
  if (!active) {
    return base;
  }
  const reason = rows
    .filter((row) => collectionInProgress(row.collectionStatus))
    .flatMap((row) => row.jobs)
    .find((job) => job.status === "FAILED" && job.errorMessage?.trim())
    ?.errorMessage?.trim();
  return reason ? `${base}. ${reason}` : base;
}

function jobPhrases(jobs: ProgressJob[]): { text: string; failed: boolean }[] {
  return jobs.flatMap((job) => {
    const label = JOB_LABELS[job.jobType] ?? job.jobType;
    if (job.status === "SUCCESS") {
      return [{ text: `${label} done`, failed: false }];
    }
    if (job.status === "RUNNING" || job.status === "RETRY") {
      return [{ text: `${label} running`, failed: false }];
    }
    if (job.status === "FAILED") {
      const reason = job.errorMessage?.trim();
      return [{ text: reason ? `${label} failed: ${reason}` : `${label} failed`, failed: true }];
    }
    return [];
  });
}
