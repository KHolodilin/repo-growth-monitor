import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { api, type SearchRunResults } from "../lib/api";
import { formatDelta, formatNumber, formatRank } from "../lib/utils";
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
          Last checked: {data.run.businessDate} · tracked position{" "}
          {formatRank(data.run.trackedRepositoryPosition, data.query.resultLimit)}
        </p>
      </div>
      <Card>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-muted-foreground">
              <th className="py-2">#</th>
              <th>Repository</th>
              <th>Owner</th>
              <th className="text-right">Stars</th>
              <th className="text-right">Forks</th>
              <th>Language</th>
              <th className="text-right">Δ</th>
            </tr>
          </thead>
          <tbody>
            {data.rows.map((row) => {
              const mine = row.result.position === data.run.trackedRepositoryPosition;
              return (
                <tr key={row.result.githubRepositoryId} className={`border-t ${mine ? "bg-blue-50 font-medium" : ""}`}>
                  <td className="py-2">{row.result.position}</td>
                  <td>{row.result.fullName}</td>
                  <td>{row.result.owner}</td>
                  <td className="text-right">{formatNumber(row.result.stars)}</td>
                  <td className="text-right">{formatNumber(row.result.forks)}</td>
                  <td>{row.result.language ?? "—"}</td>
                  <td className="text-right">{formatDelta(row.positionDelta)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>
    </div>
  );
}
