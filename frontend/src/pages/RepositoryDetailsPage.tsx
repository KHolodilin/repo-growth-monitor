import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import ReactECharts from "echarts-for-react";
import {
  api,
  type CollectionRun,
  type Repository,
  type RepositoryTraffic,
  type SearchHistory,
} from "../lib/api";
import { formatDelta, formatNumber, formatRank } from "../lib/utils";
import { Button, Card } from "../components/ui";

type Tab = "overview" | "traffic" | "search";

export function RepositoryDetailsPage() {
  const { id } = useParams();
  const [tab, setTab] = useState<Tab>("overview");
  const [repo, setRepo] = useState<Repository | null>(null);
  const [traffic, setTraffic] = useState<RepositoryTraffic | null>(null);
  const [visibility, setVisibility] = useState<SearchHistory[]>([]);
  const [queryText, setQueryText] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function load() {
    if (!id) {
      return;
    }
    const [repository, trafficData, searchData] = await Promise.all([
      api<Repository>(`/api/v1/repositories/${id}`),
      api<RepositoryTraffic>(`/api/v1/repositories/${id}/traffic?period=90d`),
      api<SearchHistory[]>(`/api/v1/repositories/${id}/search-visibility`),
    ]);
    setRepo(repository);
    setTraffic(trafficData);
    setVisibility(searchData);
  }

  useEffect(() => {
    load().catch((err: Error) => setError(err.message));
  }, [id]);

  async function collect() {
    await api(`/api/v1/repositories/${id}/collect`, { method: "POST" });
    await load();
  }

  async function createQuery() {
    if (!queryText.trim()) {
      return;
    }
    await api(`/api/v1/repositories/${id}/search-queries`, {
      method: "POST",
      body: JSON.stringify({ name: queryText, query: queryText, enabled: true, resultLimit: 50 }),
    });
    setQueryText("");
    await load();
  }

  async function runQuery(queryId: number) {
    await api(`/api/v1/search-queries/${queryId}/run`, { method: "POST" });
    await load();
  }

  if (error) {
    return <p className="text-red-600">{error}</p>;
  }
  if (!repo || !traffic) {
    return <p className="text-muted-foreground">Loading repository…</p>;
  }

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">{repo.fullName}</h1>
          <p className="text-sm text-muted-foreground">{repo.description}</p>
        </div>
        <Button onClick={() => void collect()}>Collect now</Button>
      </div>
      <div className="flex gap-2">
        {(["overview", "traffic", "search"] as Tab[]).map((item) => (
          <Button key={item} className={tab === item ? "" : "bg-muted text-foreground"} onClick={() => setTab(item)}>
            {item === "search" ? "Search Visibility" : item[0].toUpperCase() + item.slice(1)}
          </Button>
        ))}
      </div>
      {tab === "overview" && <Overview repo={repo} lastCollection={traffic.lastCollection} />}
      {tab === "traffic" && <TrafficPanel traffic={traffic} />}
      {tab === "search" && (
        <SearchPanel
          visibility={visibility}
          queryText={queryText}
          setQueryText={setQueryText}
          onCreate={() => void createQuery()}
          onRun={runQuery}
        />
      )}
    </div>
  );
}

function Overview({ repo, lastCollection }: { repo: Repository; lastCollection?: CollectionRun }) {
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Card>
        <h2 className="mb-3 font-medium">Overview</h2>
        <dl className="grid grid-cols-2 gap-2 text-sm">
          <dt className="text-muted-foreground">Owner</dt>
          <dd>{repo.owner.login}</dd>
          <dt className="text-muted-foreground">Visibility</dt>
          <dd>{repo.visibility}</dd>
          <dt className="text-muted-foreground">Stars</dt>
          <dd>{formatNumber(repo.stars)}</dd>
          <dt className="text-muted-foreground">Forks</dt>
          <dd>{formatNumber(repo.forks)}</dd>
          <dt className="text-muted-foreground">GitHub</dt>
          <dd>
            <a className="text-primary" href={`https://github.com/${repo.fullName}`} target="_blank" rel="noreferrer">
              {repo.fullName}
            </a>
          </dd>
        </dl>
      </Card>
      <Card>
        <h2 className="mb-3 font-medium">Collection Status</h2>
        {lastCollection ? <CollectionStatus run={lastCollection} /> : <p className="text-sm text-muted-foreground">No collection yet.</p>}
      </Card>
    </div>
  );
}

function CollectionStatus({ run }: { run: CollectionRun }) {
  return (
    <div className="space-y-2 text-sm">
      <div>
        {run.businessDate} · {run.status} · {run.successfulJobs} / {run.plannedJobs} successful
      </div>
      {run.jobs.map((job) => (
        <div key={job.jobType}>
          {job.status === "SUCCESS" ? "✓" : job.status === "FAILED" ? "✗" : "…"} {job.jobType}
          {job.errorMessage ? ` — ${job.errorMessage}` : ""}
        </div>
      ))}
    </div>
  );
}

function TrafficPanel({ traffic }: { traffic: RepositoryTraffic }) {
  const option = useMemo(
    () => ({
      tooltip: { trigger: "axis" },
      legend: { data: ["Views", "Visitors", "Clones", "Unique cloners"] },
      dataZoom: [{ type: "inside" }, { type: "slider" }],
      xAxis: { type: "category", data: traffic.history.map((point) => point.trafficDate) },
      yAxis: { type: "value" },
      series: [
        { name: "Views", type: "line", data: traffic.history.map((point) => point.views) },
        { name: "Visitors", type: "line", data: traffic.history.map((point) => point.uniqueVisitors) },
        { name: "Clones", type: "line", data: traffic.history.map((point) => point.clones) },
        { name: "Unique cloners", type: "line", data: traffic.history.map((point) => point.uniqueCloners) },
      ],
    }),
    [traffic],
  );

  return (
    <div className="space-y-4">
      <Card>
        <h2 className="mb-3 font-medium">Traffic history</h2>
        <ReactECharts option={option} style={{ height: 360 }} />
      </Card>
      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <h2 className="mb-3 font-medium">Referrers</h2>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-muted-foreground">
                <th>Source</th>
                <th className="text-right">Views</th>
                <th className="text-right">Visitors</th>
              </tr>
            </thead>
            <tbody>
              {traffic.referrers.map((row) => (
                <tr key={row.referrer} className="border-t">
                  <td className="py-2">{row.referrer}</td>
                  <td className="text-right">{formatNumber(row.views)}</td>
                  <td className="text-right">{formatNumber(row.uniqueVisitors)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
        <Card>
          <h2 className="mb-3 font-medium">Popular paths</h2>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-muted-foreground">
                <th>Path</th>
                <th className="text-right">Views</th>
                <th className="text-right">Visitors</th>
              </tr>
            </thead>
            <tbody>
              {traffic.paths.map((row) => (
                <tr key={row.path} className="border-t">
                  <td className="py-2">
                    <div>{row.path}</div>
                    <div className="text-xs text-muted-foreground">{row.title}</div>
                  </td>
                  <td className="text-right">{formatNumber(row.views)}</td>
                  <td className="text-right">{formatNumber(row.uniqueVisitors)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      </div>
    </div>
  );
}

function SearchPanel({
  visibility,
  queryText,
  setQueryText,
  onCreate,
  onRun,
}: {
  visibility: SearchHistory[];
  queryText: string;
  setQueryText: (value: string) => void;
  onCreate: () => void;
  onRun: (id: number) => void;
}) {
  return (
    <div className="space-y-4">
      <Card className="flex gap-2">
        <input
          className="flex-1 rounded-md border px-3 py-2 text-sm"
          placeholder="transactional outbox language:java"
          value={queryText}
          onChange={(event) => setQueryText(event.target.value)}
        />
        <Button onClick={onCreate}>Add query</Button>
      </Card>
      <Card>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-muted-foreground">
              <th>Search Query</th>
              <th className="text-right">Rank</th>
              <th className="text-right">7d</th>
              <th className="text-right">30d</th>
              <th className="text-right">Best</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {visibility.map((item) => {
              const latestRunId = item.points.at(-1)?.searchRunId;
              return (
                <tr key={item.query.id} className="border-t">
                  <td className="py-3">
                    {latestRunId ? (
                      <Link className="text-primary hover:underline" to={`/search-runs/${latestRunId}`}>
                        {item.query.name}
                      </Link>
                    ) : (
                      item.query.name
                    )}
                  </td>
                  <td className="text-right">{formatRank(item.currentRank, item.query.resultLimit)}</td>
                  <td className="text-right">{formatDelta(item.change7d)}</td>
                  <td className="text-right">{formatDelta(item.change30d)}</td>
                  <td className="text-right">{formatRank(item.bestRank, item.query.resultLimit)}</td>
                  <td className="text-right">
                    <Button className="bg-muted text-foreground" onClick={() => onRun(item.query.id)}>
                      Run
                    </Button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>
      {visibility.map((item) => (
        <RankChart key={item.query.id} history={item} />
      ))}
    </div>
  );
}

function RankChart({ history }: { history: SearchHistory }) {
  const option = {
    title: { text: history.query.name, left: 0, textStyle: { fontSize: 14 } },
    tooltip: { trigger: "axis" },
    xAxis: { type: "category", data: history.points.map((point) => point.date) },
    yAxis: {
      type: "value",
      inverse: true,
      min: 1,
      name: "Rank",
    },
    series: [
      {
        type: "line",
        connectNulls: false,
        data: history.points.map((point) => point.position),
      },
    ],
  };
  return (
    <Card>
      <ReactECharts option={option} style={{ height: 280 }} />
    </Card>
  );
}
