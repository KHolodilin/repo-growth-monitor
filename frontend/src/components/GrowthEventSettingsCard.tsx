import { useEffect, useState } from "react";
import { api, type GrowthEventSetting } from "../lib/api";
import { AUTOMATIC_SETTING_LABELS } from "../lib/growthEvents";
import { useMutationAccess } from "../lib/mutationAccess";
import { Card } from "./ui";

export function GrowthEventSettingsCard({ repositoryId }: { repositoryId: number }) {
  const { allowMutations, blocked } = useMutationAccess();
  const [settings, setSettings] = useState<GrowthEventSetting[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api<GrowthEventSetting[]>(`/api/v1/repositories/${repositoryId}/growth-event-settings`)
      .then((data) => {
        if (!cancelled) {
          setSettings(data);
        }
      })
      .catch((err: Error) => {
        if (!cancelled) {
          setError(err.message);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [repositoryId]);

  const byType = new Map(settings.map((item) => [item.eventType, item]));

  async function toggle(eventType: string, enabled: boolean) {
    if (!allowMutations) {
      setError(blocked ?? "Admin role required");
      return;
    }
    setError(null);
    const previous = settings;
    setSettings((current) =>
      current.map((item) => (item.eventType === eventType ? { ...item, enabled } : item)),
    );
    try {
      const updated = await api<GrowthEventSetting[]>(`/api/v1/repositories/${repositoryId}/growth-event-settings`, {
        method: "PUT",
        body: JSON.stringify([{ eventType, enabled }]),
      });
      setSettings(updated);
    } catch (err) {
      setSettings(previous);
      setError(err instanceof Error ? err.message : "Failed to update settings");
    }
  }

  if (!allowMutations) {
    return <Card>
      <h2 className="mb-2 font-medium">Automatic events</h2>
      <p className="text-sm text-muted-foreground">Read-only configuration. An administrator can change settings and add promotion events.</p>
      {error ? <p role="alert" className="mt-2 text-sm text-red-700">{error}</p> : <details className="mt-3 text-sm">
        <summary className="cursor-pointer py-2">Current configuration ({settings.filter(item => item.enabled).length} enabled)</summary>
        <dl className="mt-2 space-y-2">{AUTOMATIC_SETTING_LABELS.map(item => <div key={item.type} className="flex justify-between gap-3"><dt>{item.label}</dt><dd>{byType.has(item.type) ? byType.get(item.type)!.enabled ? "Enabled" : "Disabled" : "Not available"}</dd></div>)}</dl>
      </details>}
    </Card>;
  }
  return (
    <Card>
      <h2 className="mb-3 font-medium">Automatic events</h2>
      <p className="mb-3 text-sm text-muted-foreground">
        Choose which automatic events to collect. Turning a type off does not delete existing events.
      </p>
      {error && <div className="mb-3 text-sm text-red-700">{error}</div>}
      <div className="space-y-2 text-sm">
        {AUTOMATIC_SETTING_LABELS.map((item) => {
          const setting = byType.get(item.type);
          return (
            <label key={item.type} className="flex cursor-pointer items-center gap-2">
              <input
                type="checkbox"
                checked={setting?.enabled ?? false}
                disabled={!setting || !allowMutations}
                title={blocked ?? undefined}
                onChange={(change) => void toggle(item.type, change.target.checked)}
              />
              <span>{item.label}</span>
            </label>
          );
        })}
      </div>
    </Card>
  );
}
