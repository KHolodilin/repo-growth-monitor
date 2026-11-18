import { useMemo, useState } from "react";
import { chartTable } from "../lib/chartExport";
import { cn } from "../lib/utils";

type SortDir = "asc" | "desc";

export function ChartDataAlternative({ option, title }: { option: object; title: string }) {
  const [open, setOpen] = useState(false);
  const table = chartTable(option);
  const [sortKey, setSortKey] = useState<string>("date");
  const [sortDir, setSortDir] = useState<SortDir>("desc");

  const rows = useMemo(() => {
    const indexes = table.dates.map((_, index) => index);
    indexes.sort((left, right) => {
      const compared =
        sortKey === "date"
          ? compareText(table.dates[left], table.dates[right])
          : compareCell(
              table.columns.find((column) => column.name === sortKey)?.values[left] ?? "",
              table.columns.find((column) => column.name === sortKey)?.values[right] ?? "",
            );
      return sortDir === "asc" ? compared : -compared;
    });
    return indexes.map((index) => ({
      date: table.dates[index],
      values: table.columns.map((column) => column.values[index] || "—"),
    }));
  }, [table, sortKey, sortDir]);

  function toggle(key: string) {
    if (sortKey === key) {
      setSortDir((current) => (current === "asc" ? "desc" : "asc"));
      return;
    }
    setSortKey(key);
    setSortDir(key === "date" ? "desc" : "asc");
  }

  return (
    <details className="mt-3 text-sm" onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary className="cursor-pointer rounded py-2 font-medium text-primary focus-visible:outline-2">
        View historical data
      </summary>
      <p className="my-2 text-xs text-muted-foreground">
        All recorded series. — means no observation; zero is a measured value.
      </p>
      {open &&
        (table.dates.length === 0 ? (
          <p>No observations for this period.</p>
        ) : (
          <div className="max-h-80 overflow-auto">
            <table className="w-full text-sm">
              <caption className="sr-only">{title} historical values</caption>
              <thead>
                <tr>
                  <SortHeader
                    label="Date / Timeline day"
                    align="left"
                    active={sortKey === "date"}
                    dir={sortDir}
                    onClick={() => toggle("date")}
                  />
                  {table.columns.map((column) => (
                    <SortHeader
                      key={column.name}
                      label={column.name}
                      align="right"
                      active={sortKey === column.name}
                      dir={sortDir}
                      onClick={() => toggle(column.name)}
                    />
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.date} className="border-t">
                    <th scope="row" className="whitespace-nowrap p-2 text-left font-normal">
                      {row.date}
                    </th>
                    {row.values.map((value, index) => (
                      <td key={table.columns[index].name} className="p-2 text-right">
                        {value}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
    </details>
  );
}

function SortHeader({
  label,
  align,
  active,
  dir,
  onClick,
}: {
  label: string;
  align: "left" | "right";
  active: boolean;
  dir: SortDir;
  onClick: () => void;
}) {
  return (
    <th scope="col" className={cn("p-2 font-medium", align === "left" ? "text-left" : "text-right")}>
      <button
        type="button"
        className={cn("inline-flex min-h-11 items-center gap-1 rounded px-1", align === "right" && "flex-row-reverse")}
        aria-pressed={active}
        onClick={onClick}
      >
        {label}
        <span aria-hidden="true">{active ? (dir === "asc" ? "↑" : "↓") : ""}</span>
      </button>
    </th>
  );
}

function compareText(left: string, right: string) {
  return left.localeCompare(right, undefined, { numeric: true, sensitivity: "base" });
}

function compareCell(left: string, right: string) {
  const leftNumber = parseNumber(left);
  const rightNumber = parseNumber(right);
  if (leftNumber != null && rightNumber != null) {
    return leftNumber - rightNumber;
  }
  if (leftNumber != null) {
    return 1;
  }
  if (rightNumber != null) {
    return -1;
  }
  return compareText(left, right);
}

function parseNumber(value: string) {
  if (!value || value === "—") {
    return null;
  }
  const normalized = value.replace(/,/g, "");
  const number = Number(normalized);
  return Number.isFinite(number) ? number : null;
}
