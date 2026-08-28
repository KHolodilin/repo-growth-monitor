import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { api, type SearchRunResults } from "../lib/api";
import { formatRank, formatSyncTime } from "../lib/utils";
import { Card } from "../components/ui";
import { SearchResultsTable } from "../components/SearchResultsTable";

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
        <SearchResultsTable
          rows={data.rows}
          trackedPosition={data.run.trackedRepositoryPosition}
        />
      </Card>
    </div>
  );
}
