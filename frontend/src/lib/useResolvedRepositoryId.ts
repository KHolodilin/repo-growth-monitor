import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { resolveRepository } from "./resolveRepository";

/**
 * Resolve the current repository id from either legacy `:id` / `:repositoryId`
 * params (tests) or canonical `:owner/:repository` path params.
 */
export function useResolvedRepositoryId(owner?: string, repository?: string) {
  const params = useParams();
  const directId = params.id ?? params.repositoryId;
  const pathOwner = owner ?? params.owner;
  const pathRepository = repository ?? params.repository;
  const [id, setId] = useState<string | undefined>(directId);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (directId) {
      setId(directId);
      setError(null);
      return;
    }
    if (!pathOwner || !pathRepository) {
      setId(undefined);
      return;
    }
    let cancelled = false;
    setId(undefined);
    setError(null);
    resolveRepository(pathOwner, pathRepository)
      .then((repo) => {
        if (!cancelled) {
          setId(String(repo.id));
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "Failed to resolve repository");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [directId, pathOwner, pathRepository]);

  return { id, error };
}
