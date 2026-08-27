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
