import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api, type Repository } from "../lib/api";
import { activityClass, formatActivityPresentation } from "../lib/utils";
import { Button, Card } from "../components/ui";

export function RepositoriesPage() {
  const [repos, setRepos] = useState<Repository[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function load(refresh = false) {
    setLoading(true);
    setError(null);
    try {
      const data = await api<Repository[]>(`/api/v1/repositories?refresh=${refresh}`);
      setRepos(data);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load(true);
  }, []);

  async function toggle(repo: Repository) {
    const updated = await api<Repository>(`/api/v1/repositories/${repo.id}/tracking`, {
      method: "POST",
      body: JSON.stringify({ enabled: !repo.trackingEnabled }),
    });
    setRepos((current) => current.map((item) => (item.id === updated.id ? updated : item)));
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Repositories</h1>
          <p className="text-sm text-muted-foreground">Select GitHub repositories to monitor.</p>
        </div>
        <Button disabled={loading} onClick={() => void load(true)}>
          Refresh from GitHub
        </Button>
      </div>
      {error && <p className="text-red-600">{error}</p>}
      <Card>
        <div className="space-y-3">
          {repos.map((repo) => (
            <label key={repo.id} className="flex items-center justify-between gap-4 rounded-lg border px-3 py-3">
              <div className="flex items-center gap-3">
                <input
                  type="checkbox"
                  checked={repo.trackingEnabled}
                  onChange={() => void toggle(repo)}
                />
                <div>
                  <Link className="font-medium text-primary hover:underline" to={`/repositories/${repo.id}`}>
                    {repo.fullName}
                  </Link>
                  <div className="text-xs text-muted-foreground">
                    {repo.visibility} · {repo.stars} stars · {repo.owner.login}
                  </div>
                </div>
              </div>
              <div className={`shrink-0 text-sm ${activityClass(repo.activityStatus)}`}>
                {formatActivityPresentation(repo.activityStatus, repo.lastActivityAt)}
              </div>
            </label>
          ))}
          {repos.length === 0 && !loading && (
            <p className="text-sm text-muted-foreground">No repositories yet. Configure GITHUB_TOKEN and refresh.</p>
          )}
        </div>
      </Card>
    </div>
  );
}
