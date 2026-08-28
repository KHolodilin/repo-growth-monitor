import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatNumber(value?: number | null) {
  if (value === null || value === undefined) {
    return "—";
  }
  return new Intl.NumberFormat("en-US").format(value);
}

export function formatRank(position: number | null | undefined, limit = 50) {
  if (position === null || position === undefined) {
    return `>${limit}`;
  }
  return `#${position}`;
}

export function formatGrowth(value?: number | null) {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return null;
  }
  const rounded = Math.round(value * 10) / 10;
  const abs = Math.abs(rounded);
  const text = Number.isInteger(abs) ? String(abs) : abs.toFixed(1);
  if (rounded > 0) {
    return { direction: "up" as const, label: `↑ ${text}%` };
  }
  if (rounded < 0) {
    return { direction: "down" as const, label: `↓ ${text}%` };
  }
  return { direction: "flat" as const, label: "→ 0%" };
}

export function formatSyncTime(iso?: string | null) {
  if (!iso) {
    return null;
  }
  return new Intl.DateTimeFormat("en-US", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date(iso));
}

export function growthClass(direction?: "up" | "down" | "flat") {
  if (direction === "up") {
    return "text-emerald-700";
  }
  if (direction === "down") {
    return "text-red-700";
  }
  return "text-muted-foreground";
}

export function formatDelta(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return "—";
  }
  if (value > 0) {
    return `↑${value}`;
  }
  if (value < 0) {
    return `↓${Math.abs(value)}`;
  }
  return "0";
}

export function formatPositionDelta(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return "NEW";
  }
  return formatDelta(value);
}

export function formatActivity(status?: string | null) {
  if (status === "ACTIVE") {
    return "Active";
  }
  if (status === "LOW_ACTIVITY") {
    return "Low";
  }
  if (status === "INACTIVE") {
    return "Inactive";
  }
  if (status === "ARCHIVED") {
    return "Archived";
  }
  return "Unknown";
}

export function formatActivityPresentation(status?: string | null, iso?: string | null) {
  const label = formatActivity(status);
  if (label === "Unknown") {
    return "Unknown";
  }
  const relative = formatRelativeTime(iso);
  if (!iso || relative === "—" || label === "Archived") {
    return label;
  }
  return `${label} · ${relative}`;
}

export function activityClass(status?: string | null) {
  if (status === "ACTIVE") {
    return "text-emerald-700";
  }
  if (status === "LOW_ACTIVITY") {
    return "text-amber-700";
  }
  if (status === "INACTIVE" || status === "ARCHIVED") {
    return "text-slate-500";
  }
  return "text-muted-foreground";
}

export function calendarDates(dates: string[]): string[] {
  const sorted = [...new Set(dates)].sort();
  if (sorted.length === 0) {
    return [];
  }
  const out: string[] = [];
  const cursor = new Date(`${sorted[0]}T00:00:00Z`);
  const last = new Date(`${sorted[sorted.length - 1]}T00:00:00Z`);
  while (cursor <= last) {
    out.push(cursor.toISOString().slice(0, 10));
    cursor.setUTCDate(cursor.getUTCDate() + 1);
  }
  return out;
}

export function formatRelativeTime(iso?: string | null) {
  if (!iso) {
    return "—";
  }
  const then = new Date(iso).getTime();
  const days = Math.max(0, Math.floor((Date.now() - then) / 86_400_000));
  if (days === 0) {
    return "today";
  }
  if (days === 1) {
    return "1d ago";
  }
  if (days < 30) {
    return `${days}d ago`;
  }
  const months = Math.floor(days / 30);
  if (months < 12) {
    return `${months}mo ago`;
  }
  const years = Math.floor(days / 365);
  return `${years}y ago`;
}
