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
