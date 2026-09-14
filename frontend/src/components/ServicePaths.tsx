const HINT =
  "Repository tabs such as Insights, Issues or Pull Requests. Mostly your own visits, not readers arriving at the project.";

export function ServicePathToggle({
  checked,
  onChange,
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
}) {
  return (
    <label className="inline-flex cursor-pointer items-center gap-1.5 whitespace-nowrap" title={HINT}>
      <input
        type="checkbox"
        className="h-3.5 w-3.5 cursor-pointer accent-primary"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
      />
      Show service pages
    </label>
  );
}

export function ServicePathBadge() {
  return (
    <span
      className="ml-1.5 rounded border px-1 py-px align-middle text-[0.625rem] uppercase text-muted-foreground"
      title={HINT}
    >
      service
    </span>
  );
}
