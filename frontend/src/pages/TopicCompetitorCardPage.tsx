import { useEffect, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { api, type CompetitorCard, type Repository, type TopicHistory } from "../lib/api";
import { CompetitorCardView } from "./CompetitorCardPage";

function parseScope(value: string | null): "all" | "language" {
  return value === "language" ? "language" : "all";
}

export function topicCompetitorPath(repositoryId: number, topic: string, githubRepositoryId: number, scope: "all" | "language") {
  const path = `/repositories/${repositoryId}/topics/${encodeURIComponent(topic)}/competitors/${githubRepositoryId}`;
  return scope === "language" ? `${path}?scope=language` : path;
}

export function TopicCompetitorCardPage() {
  const params = useParams();
  const [searchParams] = useSearchParams();
  const repositoryId = Number(params.repositoryId);
  const topicName = params.topic ? decodeURIComponent(params.topic) : "";
  const githubRepositoryId = Number(params.githubRepositoryId);
  const scope = parseScope(searchParams.get("scope"));
  const [repo, setRepo] = useState<Repository | null>(null);
  const [history, setHistory] = useState<TopicHistory | null>(null);
  const [card, setCard] = useState<CompetitorCard | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!repositoryId || !topicName || !githubRepositoryId) return;
    let cancelled = false;
    setError(null);
    const topicPath = encodeURIComponent(topicName);
    Promise.all([
      api<Repository>(`/api/v1/repositories/${repositoryId}`),
      api<TopicHistory>(`/api/v1/repositories/${repositoryId}/topics/${topicPath}/history?scope=${scope}`),
      api<CompetitorCard>(`/api/v1/repositories/${repositoryId}/topics/${topicPath}/competitors/${githubRepositoryId}?scope=${scope}`),
    ])
      .then(([repository, topicHistory, competitor]) => {
        if (cancelled) return;
        setRepo(repository);
        setHistory(topicHistory);
        setCard(competitor);
      })
      .catch((reason: unknown) => {
        if (!cancelled) setError(reason instanceof Error ? reason.message : "Failed to load the competitor card");
      });
    return () => {
      cancelled = true;
    };
  }, [repositoryId, topicName, githubRepositoryId, scope]);

  if (error) return <p className="text-sm text-destructive">{error}</p>;
  if (!repo || !history || !card) return <p className="text-sm text-muted-foreground">Loading competitor card...</p>;

  const scopeQuery = scope === "language" ? "&scope=language" : "";
  const topicTo = `/repositories/${repo.id}/topics/${encodeURIComponent(history.watch.topic)}${scope === "language" ? "?scope=language" : ""}`;

  return (
    <CompetitorCardView
      items={[
        { label: "Portfolio", to: "/dashboard" },
        {
          label: repo.fullName,
          repoSwitcher: {
            currentId: repo.id,
            hrefFor: (id) => `/repositories/${id}?tab=topics`,
          },
        },
        { label: "Topics Visibility", to: `/repositories/${repo.id}?tab=topics${scopeQuery}` },
        { label: history.watch.topic, to: topicTo },
      ]}
      card={card}
      chartId={`${repositoryId}:topic-competitor:${topicName}:${githubRepositoryId}`}
    />
  );
}
