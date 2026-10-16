import { NO_ACCESS, TRAFFIC_PERMISSION, type TokenState } from "../lib/githubAccess";

export function RepositoryAccessNote({
  accessible,
  trafficPermissionMissing,
  tokenState,
}: {
  accessible?: boolean;
  trafficPermissionMissing?: boolean;
  tokenState?: TokenState | null;
}) {
  const known = tokenState === "OK" || tokenState === "RATE_LIMITED";
  const lost = accessible === false && known;
  const traffic = trafficPermissionMissing === true && known;
  if (!lost && !traffic) {
    return null;
  }
  return (
    <div className="mt-1 space-y-1 text-xs text-amber-800">
      {lost && (
        <p>
          <span className="mr-1 font-semibold" aria-hidden="true">
            !
          </span>
          {NO_ACCESS}
        </p>
      )}
      {traffic && <p>{TRAFFIC_PERMISSION}</p>}
    </div>
  );
}
