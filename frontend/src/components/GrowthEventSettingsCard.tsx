import { useEffect, useState } from "react";
import { api, type GrowthEventSetting } from "../lib/api";
import { AUTOMATIC_SETTING_LABELS } from "../lib/growthEvents";
import { Card } from "./ui";

export function GrowthEventSettingsCard({ repositoryId }: { repositoryId: number }) {
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
                disabled={!setting}
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
