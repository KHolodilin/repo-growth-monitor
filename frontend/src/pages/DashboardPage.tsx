import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api, type Portfolio } from "../lib/api";
import { formatNumber } from "../lib/utils";
import { Button, Card } from "../components/ui";

const PERIODS = ["7d", "30d", "90d", "1y", "all"] as const;

export function DashboardPage() {
  const [period, setPeriod] = useState("30d");
  const [data, setData] = useState<Portfolio | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Portfolio>(`/api/v1/portfolio?period=${period}`)
      .then(setData)
      .catch((err: Error) => setError(err.message));
  }, [period]);

  if (error) {
    return <p className="text-red-600">{error}</p>;
  }
  if (!data) {
    return <p className="text-muted-foreground">Loading portfolio…</p>;
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Portfolio</h1>
        <div className="flex gap-2">
          {PERIODS.map((item) => (
            <Button
              key={item}
              className={period === item ? "" : "bg-muted text-foreground"}
              onClick={() => setPeriod(item)}
            >
              {item}
            </Button>
          ))}
        </div>
      </div>
      <div className="grid grid-cols-2 gap-4 md:grid-cols-5">
        <Kpi label="Repositories" value={data.repositories} />
        <Kpi label="Views" value={data.views} />
        <Kpi label="Visitors" value={data.visitors} />
        <Kpi label="Clones" value={data.clones} />
        <Kpi label="Stars" value={data.stars} />
      </div>
      <Card>
        <table className="w-full text-sm">
          <thead className="text-left text-muted-foreground">
            <tr>
              <th className="py-2">Repository</th>
              <th className="py-2 text-right">Visitors</th>
              <th className="py-2 text-right">Views</th>
              <th className="py-2 text-right">Clones</th>
              <th className="py-2 text-right">Stars</th>
            </tr>
          </thead>
          <tbody>
            {data.table.map((row) => (
              <tr key={row.id} className="border-t">
                <td className="py-3">
                  <Link className="text-primary hover:underline" to={`/repositories/${row.id}`}>
                    {row.fullName}
                  </Link>
                </td>
                <td className="text-right">{formatNumber(row.visitors)}</td>
                <td className="text-right">{formatNumber(row.views)}</td>
                <td className="text-right">{formatNumber(row.clones)}</td>
                <td className="text-right">{formatNumber(row.stars)}</td>
              </tr>
            ))}
            {data.table.length === 0 && (
              <tr>
                <td className="py-6 text-muted-foreground" colSpan={5}>
                  No tracked repositories yet. Open Repositories and enable tracking.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </Card>
    </div>
  );
}

function Kpi({ label, value }: { label: string; value: number }) {
  return (
    <Card>
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className="mt-2 text-2xl font-semibold">{formatNumber(value)}</div>
    </Card>
  );
}
