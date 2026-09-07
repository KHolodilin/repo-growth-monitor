import { Link } from "react-router-dom";
import { formatNumber, formatSyncTime } from "../lib/utils";
import { ReferrerSourceIcon } from "./ReferrerSourceIcon";
import { Card } from "./ui";

const TOP_ROWS = 5;

type CardRow = { key: string; title: string; subtitle?: string; visitors: number; views: number };

export function SnapshotCards({
  repositoryId,
  referrers,
  referrerSnapshotAt,
  paths,
  pathSnapshotAt,
}: {
  repositoryId: number;
  referrers: { referrer: string; views: number; uniqueVisitors: number }[];
  referrerSnapshotAt?: string;
  paths: { path: string; title?: string; views: number; uniqueVisitors: number }[];
  pathSnapshotAt?: string;
}) {
  const referrerRows = referrers.map((row) => ({
    key: row.referrer,
    title: row.referrer,
    visitors: row.uniqueVisitors,
    views: row.views,
  }));
  const pathRows = paths.map((row) => ({
    key: row.path,
    title: row.path,
    subtitle: row.title,
    visitors: row.uniqueVisitors,
    views: row.views,
  }));

  return (
    <div className="grid gap-4 md:grid-cols-2">
      <SnapshotCard
        title="Top Referrers"
        firstColumn="Source"
        rows={referrerRows}
        snapshotAt={referrerSnapshotAt}
        icons
        historyTo={`/repositories/${repositoryId}/traffic/history?kind=referrers`}
      />
      <SnapshotCard
        title="Popular Paths"
        firstColumn="Path"
        rows={pathRows}
        snapshotAt={pathSnapshotAt}
        historyTo={`/repositories/${repositoryId}/traffic/history?kind=paths`}
      />
    </div>
  );
}

function SnapshotCard({
  title,
  firstColumn,
  rows,
  snapshotAt,
  historyTo,
  icons = false,
}: {
  title: string;
  firstColumn: string;
  rows: CardRow[];
  snapshotAt?: string;
  historyTo: string;
  icons?: boolean;
}) {
  return (
    <Card>
      <div className="mb-1 flex items-center justify-between gap-3">
        <h2 className="font-medium">{title}</h2>
        <Link className="text-sm font-medium text-primary" to={historyTo}>
          History →
        </Link>
      </div>
      <div className="mb-3 text-xs text-muted-foreground">
        {snapshotAt ? `Snapshot: ${formatSyncTime(snapshotAt)}` : "No snapshot yet"}
      </div>
      <div className="overflow-x-auto">
        <table className="w-full table-fixed text-sm">
          <thead>
            <tr className="text-left text-muted-foreground">
              <th className="pr-3">{firstColumn}</th>
              <th className="w-[4.75rem] whitespace-nowrap pl-2 text-right">Visitors</th>
              <th className="w-16 whitespace-nowrap pl-2 text-right">Views</th>
            </tr>
          </thead>
          <tbody>
            {rows.slice(0, TOP_ROWS).map((row) => (
              <tr key={row.key} className="border-t">
                <td className="max-w-0 py-2 pr-3 align-top">
                  <div className="flex items-start gap-2">
                    {icons && <ReferrerSourceIcon source={row.title} />}
                    <div>
                      <div className="[overflow-wrap:anywhere]">{wrapPath(row.title)}</div>
                      {row.subtitle && <div className="text-xs text-muted-foreground">{row.subtitle}</div>}
                    </div>
                  </div>
                </td>
                <td className="whitespace-nowrap pl-2 text-right align-top">{formatNumber(row.visitors)}</td>
                <td className="whitespace-nowrap pl-2 text-right align-top">{formatNumber(row.views)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
}

function wrapPath(value: string) {
  return value.replaceAll("/", "/\u200b");
}
