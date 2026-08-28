import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, type SearchRunResults } from "../lib/api";
import { formatActivity, formatNumber, formatPositionDelta, formatRank, formatRelativeTime, formatSyncTime } from "../lib/utils";
import { Card } from "../components/ui";

export function SearchResultsPage() {
  const { id } = useParams();
  const [data, setData] = useState<SearchRunResults | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) {
      return;
    }
    api<SearchRunResults>(`/api/v1/search-runs/${id}/results`)
      .then(setData)
      .catch((err: Error) => setError(err.message));
  }, [id]);

  if (error) {
    return <p className="text-red-600">{error}</p>;
  }
  if (!data) {
    return <p className="text-muted-foreground">Loading search results…</p>;
  }

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{data.query.query}</h1>
        <p className="text-sm text-muted-foreground">
          Last checked: {formatSyncTime(data.run.completedAt) ?? data.run.businessDate} · tracked position{" "}
          {formatRank(data.run.trackedRepositoryPosition, data.query.resultLimit)}
          {data.run.enrichmentStatus ? ` · Enrichment: ${data.run.enrichmentStatus}` : ""}
        </p>
      </div>
      <Card>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[880px] table-fixed text-sm">
            <thead>
              <tr className="text-left text-muted-foreground">
                <th className="w-10 whitespace-nowrap px-4 py-2">#</th>
                <th className="whitespace-nowrap px-4 py-2">Repository</th>
                <th className="w-[7.5rem] whitespace-nowrap px-4 py-2 text-right">Stars</th>
                <th className="w-[7.5rem] whitespace-nowrap px-4 py-2 text-right">Watchers</th>
                <th className="w-[7.5rem] whitespace-nowrap px-4 py-2 text-right">Forks</th>
                <th className="w-[8.5rem] whitespace-nowrap px-4 py-2 text-right">Contributors</th>
                <th className="w-[8.5rem] whitespace-nowrap px-4 py-2">Last Activity</th>
                <th className="w-[7rem] whitespace-nowrap px-4 py-2">Activity</th>
                <th className="w-16 whitespace-nowrap px-4 py-2 text-right">Δ</th>
              </tr>
            </thead>
            <tbody>
              {data.rows.map((row) => {
                const mine = row.result.position === data.run.trackedRepositoryPosition;
                return (
                  <tr
                    key={row.result.githubRepositoryId}
                    className={`border-t ${mine ? "bg-blue-50 font-medium" : ""}`}
                    title={
                      row.result.metadataUpdatedAt
                        ? `Repository metadata updated: ${formatSyncTime(row.result.metadataUpdatedAt)}`
                        : undefined
                    }
                  >
                    <td className="whitespace-nowrap px-4 py-2.5">{row.result.position}</td>
                    <td className="whitespace-nowrap px-4 py-2.5">
                      {mine ? (
                        <Link className="text-primary hover:underline" to={`/repositories/${data.query.repositoryId}`}>
                          {row.result.fullName}
                        </Link>
                      ) : (
                        <a
                          className="text-primary hover:underline"
                          href={row.result.htmlUrl ?? `https://github.com/${row.result.fullName}`}
                          target="_blank"
                          rel="noreferrer"
                        >
                          {row.result.fullName}
                        </a>
                      )}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2.5 text-right">{formatNumber(row.result.stars)}</td>
                    <td className="whitespace-nowrap px-4 py-2.5 text-right">{formatNumber(row.result.watchers)}</td>
                    <td className="whitespace-nowrap px-4 py-2.5 text-right">{formatNumber(row.result.forks)}</td>
                    <td className="whitespace-nowrap px-4 py-2.5 text-right">{formatNumber(row.result.contributors)}</td>
                    <td className="whitespace-nowrap px-4 py-2.5">{formatRelativeTime(row.result.activityAt)}</td>
                    <td className="whitespace-nowrap px-4 py-2.5">{formatActivity(row.result.activityStatus)}</td>
                    <td className="whitespace-nowrap px-4 py-2.5 text-right">{formatPositionDelta(row.positionDelta)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
