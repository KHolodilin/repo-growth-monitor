import { useId } from "react";
const HINT = "Include pages restricted to repository owners or users with additional permissions, such as GitHub Traffic. Public pages like Issues and Pull Requests remain visible.";

export function ServicePathToggle({ checked, onChange }: { checked: boolean; onChange: (next: boolean) => void }) {
  const id = useId();
  return <span className="inline-flex max-w-full flex-wrap items-center gap-2">
    <label className="inline-flex min-h-9 cursor-pointer items-center gap-1.5">
      <input type="checkbox" className="h-3.5 w-3.5 cursor-pointer accent-primary" checked={checked} aria-describedby={id} onChange={event => onChange(event.target.checked)} />
      Show restricted pages
    </label>
    <details className="relative inline-block">
      <summary aria-label="About restricted pages" className="cursor-pointer rounded border px-2 py-1">?</summary>
      <span id={id} className="fixed inset-x-4 top-1/3 z-30 mt-1 block md:absolute md:inset-x-auto md:right-0 md:top-auto md:w-80 whitespace-normal rounded border bg-card p-3 text-sm shadow">{HINT}</span>
    </details>
  </span>;
}
export function ServicePathBadge() {
  return <span className="ml-1.5 rounded border px-1 py-px align-middle text-[0.625rem] uppercase text-muted-foreground" title={HINT}>restricted</span>;
}
