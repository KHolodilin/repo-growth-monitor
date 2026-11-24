import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { api, type SearchRunResults } from "../lib/api";
import { formatRank, formatSyncTime } from "../lib/utils";
import { Card } from "../components/ui";
import { SearchResultsTable } from "../components/SearchResultsTable";

export function SearchResultsPage() {
  const { id } = useParams();
  const loadKey = id ?? "";
  const [seenKey, setSeenKey] = useState(loadKey);
  const [data, setData] = useState<SearchRunResults | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (loadKey !== seenKey) {
    setSeenKey(loadKey);
    setData(null);
    setError(null);
  }

  useEffect(() => {
    if (!id) {
      return;
    }
    const abort = new AbortController();
    let cancelled = false;
    api<SearchRunResults>(`/api/v1/admin/search-runs/${id}/results`, { signal: abort.signal })
      .then((next) => {
        if (cancelled || abort.signal.aborted) return;
        setData(next);
        setError(null);
      })
      .catch((err: unknown) => {
        if (cancelled || abort.signal.aborted) return;
        setError(err instanceof Error ? err.message : String(err));
      });
    return () => {
      cancelled = true;
      abort.abort();
    };
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
          Last checked: {formatSyncTime(data.run.completedAt) ?? data.run.businessDate} · tracked
          position {formatRank(data.run.trackedRepositoryPosition, data.query.resultLimit)}
          {data.run.enrichmentStatus ? ` · Enrichment: ${data.run.enrichmentStatus}` : ""}
        </p>
      </div>
      <Card>
        <SearchResultsTable
          rows={data.rows}
          repositoryId={data.query.repositoryId}
          trackedPosition={data.run.trackedRepositoryPosition}
        />
      </Card>
    </div>
  );
}
