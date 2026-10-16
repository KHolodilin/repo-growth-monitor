import { useEffect, useState } from "react";
import {
  api,
  type GrowthEvent,
  type ManualGrowthEventRequest,
  type RepositoryStatsHistory,
  type RepositoryTraffic,
  type SearchHistory,
} from "../lib/api";
import { eventWindowComparison, signedCount, type EventWindowComparison, type RankComparison } from "../lib/eventWindow";
import {
  eventCategoryIcon,
  eventTypeLabel,
  eventUtcDate,
  fromDateTimeLocal,
  MANUAL_EVENT_TYPES,
  sourceBadge,
  toDateTimeLocal,
} from "../lib/growthEvents";
import { formatChartAxisDate, formatNumber } from "../lib/utils";
import { Button, Card } from "./ui";

const RECENT = 5;

export function GrowthEventsPanel({
  repositoryId,
  events,
  onChanged,
  onOpenEvents,
}: {
  repositoryId: number;
  events: GrowthEvent[];
  onChanged: () => void;
  onOpenEvents?: (events: GrowthEvent[]) => void;
}) {
  const [viewAll, setViewAll] = useState(false);
  const [form, setForm] = useState<GrowthEvent | "new" | null>(null);
  const [selected, setSelected] = useState<GrowthEvent[] | null>(null);
  const recent = events.slice(0, RECENT);

  function openDetails(items: GrowthEvent[]) {
    const date = eventUtcDate(items[0]);
    const sameDay = events.filter((item) => eventUtcDate(item) === date);
    setSelected(sameDay);
    onOpenEvents?.(sameDay);
  }

  return (
    <Card>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-medium">Recent Growth Events</h2>
        <div className="flex items-center gap-3">
          {events.length > 0 && (
            <button type="button" className="text-sm font-medium text-primary" onClick={() => setViewAll(true)}>
              View all →
            </button>
          )}
          <Button type="button" onClick={() => setForm("new")}>
            + Add event
          </Button>
        </div>
      </div>
      {recent.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No growth events in this period. Markers appear after collection or when you add a promotion event.
        </p>
      ) : (
        <EventRows events={recent} onOpen={openDetails} onEdit={setForm} onChanged={onChanged} />
      )}
      <p className="mt-3 text-xs text-muted-foreground">
        Events mark observed repository changes in the selected period. They do not imply that a change caused traffic.
      </p>
      {viewAll && (
        <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/40 p-4" onClick={() => setViewAll(false)}>
          <Card className="max-h-[80vh] w-full max-w-xl overflow-hidden" onClick={(event) => event.stopPropagation()}>
            <div className="mb-3 flex items-center justify-between">
              <h2 className="font-medium">All Growth Events</h2>
              <Button type="button" className="bg-muted text-foreground" onClick={() => setViewAll(false)}>
                Close
              </Button>
            </div>
            <div className="max-h-[60vh] overflow-auto">
              <EventRows events={events} onOpen={openDetails} onEdit={setForm} onChanged={onChanged} />
            </div>
          </Card>
        </div>
      )}
      {selected && (
        <EventDetailsDialog
          repositoryId={repositoryId}
          events={selected}
          onClose={() => setSelected(null)}
          onEdit={(event) => {
            setSelected(null);
            setForm(event);
          }}
          onChanged={onChanged}
        />
      )}
      {form && (
        <EventFormDialog
          repositoryId={repositoryId}
          event={form === "new" ? null : form}
          onClose={() => setForm(null)}
          onSaved={() => {
            setForm(null);
            onChanged();
          }}
        />
      )}
    </Card>
  );
}

function EventRows({
  events,
  onOpen,
  onEdit,
  onChanged,
}: {
  events: GrowthEvent[];
  onOpen: (events: GrowthEvent[]) => void;
  onEdit: (event: GrowthEvent) => void;
  onChanged: () => void;
}) {
  return (
    <div className="space-y-2 text-sm">
      {events.map((event) => (
        <div key={event.id} className="flex items-start justify-between gap-3 border-t pt-2 first:border-t-0 first:pt-0">
          <button type="button" className="min-w-0 flex-1 text-left" onClick={() => onOpen([event])}>
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground">{eventCategoryIcon(event.category)}</span>
              <span className="text-xs text-muted-foreground">{formatChartAxisDate(eventUtcDate(event))}</span>
              <span className="rounded bg-muted px-1.5 py-0.5 text-[10px] font-medium uppercase text-muted-foreground">
                {sourceBadge(event.source)}
              </span>
            </div>
            <div className="mt-0.5 font-medium">{event.title}</div>
            <div className="text-xs text-muted-foreground">{eventTypeLabel(event.type)}</div>
          </button>
          {event.source === "MANUAL" && (
            <div className="flex shrink-0 gap-2">
              <button type="button" className="text-xs font-medium text-primary" onClick={() => onEdit(event)}>
                Edit
              </button>
              <button
                type="button"
                className="text-xs font-medium text-red-700"
                onClick={() => {
                  void api(`/api/v1/growth-events/${event.id}`, { method: "DELETE" }).then(onChanged);
                }}
              >
                Delete
              </button>
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

function comparisonQueries(value: unknown) {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((item) => {
    if (!item || typeof item !== "object" || !("query" in item)) {
      return [];
    }
    const history = item as SearchHistory;
    return [{ name: history.query.name, points: history.points ?? [] }];
  });
}

function rankAfter(rank: RankComparison) {
  if (rank.kind === "no-queries") {
    return "No queries";
  }
  if (rank.kind === "none") {
    return "—";
  }
  return `${rank.name} ${rank.from} to ${rank.to}`;
}

function rankDifference(rank: RankComparison) {
  if (rank.kind !== "move") {
    return "—";
  }
  return signedCount(rank.difference);
}

export function EventDetailsDialog({
  repositoryId,
  events,
  onClose,
  onEdit,
  onChanged,
  today,
}: {
  repositoryId: number;
  events: GrowthEvent[];
  onClose: () => void;
  onEdit: (event: GrowthEvent) => void;
  onChanged: () => void;
  today?: string;
}) {
  const eventDate = events[0] ? eventUtcDate(events[0]) : "";
  const [comparison, setComparison] = useState<EventWindowComparison | null>(null);

  useEffect(() => {
    if (!eventDate) {
      return;
    }
    let cancelled = false;
    const day = today ?? new Date().toISOString().slice(0, 10);
    Promise.all([
      api<RepositoryTraffic>(`/api/v1/repositories/${repositoryId}/traffic?period=all`),
      api<RepositoryStatsHistory>(`/api/v1/repositories/${repositoryId}/stats-history?period=all`),
      api<SearchHistory[]>(`/api/v1/repositories/${repositoryId}/search-visibility`),
    ])
      .then(([traffic, stats, queries]) => {
        if (cancelled) {
          return;
        }
        const trafficDays = traffic && typeof traffic === "object" && Array.isArray(traffic.traffic) ? traffic.traffic : [];
        const starDays = stats && typeof stats === "object" && Array.isArray(stats.points) ? stats.points : [];
        setComparison(
          eventWindowComparison({
            eventDate,
            today: day,
            traffic: trafficDays,
            stats: starDays,
            queries: comparisonQueries(queries),
          }),
        );
      })
      .catch(() => {
        if (cancelled) {
          return;
        }
        setComparison(
          eventWindowComparison({
            eventDate,
            today: day,
            traffic: [],
            stats: [],
            queries: [],
          }),
        );
      });
    return () => {
      cancelled = true;
    };
  }, [repositoryId, eventDate, today]);

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <Card className="max-h-[80vh] w-full max-w-lg overflow-auto" onClick={(event) => event.stopPropagation()}>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="font-medium">{events.length === 1 ? "Growth event" : `${events.length} events`}</h2>
          <Button type="button" className="bg-muted text-foreground" onClick={onClose}>
            Close
          </Button>
        </div>
        <div className="space-y-4">
          {events.map((event) => (
            <div key={event.id} className="space-y-1 text-sm">
              <div className="font-medium">{event.title}</div>
              <div className="text-muted-foreground">
                {formatChartAxisDate(eventUtcDate(event))} · {eventTypeLabel(event.type)} · {sourceBadge(event.source)}
              </div>
              {event.description && <p>{event.description}</p>}
              {event.url && (
                <a className="text-primary hover:underline" href={event.url} target="_blank" rel="noreferrer">
                  Open link
                </a>
              )}
              {event.source === "MANUAL" && (
                <div className="flex gap-3 pt-1">
                  <button type="button" className="text-xs font-medium text-primary" onClick={() => onEdit(event)}>
                    Edit
                  </button>
                  <button
                    type="button"
                    className="text-xs font-medium text-red-700"
                    onClick={() => {
                      void api(`/api/v1/growth-events/${event.id}`, { method: "DELETE" }).then(() => {
                        onChanged();
                        onClose();
                      });
                    }}
                  >
                    Delete
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
        {comparison && (
          <div className="mt-4 border-t pt-4">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-muted-foreground">
                  <th className="py-1 pr-3 font-medium" />
                  <th className="py-1 pr-3 font-medium">Before</th>
                  <th className="py-1 pr-3 font-medium">
                    After
                    {comparison.afterDays < 7 && (
                      <div className="font-normal">{comparison.afterDays} of 7 days</div>
                    )}
                  </th>
                  <th className="py-1 font-medium">Difference</th>
                </tr>
              </thead>
              <tbody>
                <ComparisonRow label="Visitors" metric={comparison.visitors} signedValues={false} />
                <ComparisonRow label="Clones" metric={comparison.clones} signedValues={false} />
                <ComparisonRow label="Stars" metric={comparison.stars} signedValues />
                <tr className="border-t">
                  <th className="py-1 pr-3 text-left font-medium">Rank</th>
                  <td className="py-1 pr-3">—</td>
                  <td className="py-1 pr-3">{rankAfter(comparison.rank)}</td>
                  <td className="py-1">{rankDifference(comparison.rank)}</td>
                </tr>
              </tbody>
            </table>
            <p className="mt-3 text-xs text-muted-foreground">A match in time does not prove a cause.</p>
          </div>
        )}
      </Card>
    </div>
  );
}

function ComparisonRow({
  label,
  metric,
  signedValues,
}: {
  label: string;
  metric: { before: number; after: number; difference: number };
  signedValues: boolean;
}) {
  const cell = signedValues ? signedCount : formatNumber;
  return (
    <tr className="border-t">
      <th className="py-1 pr-3 text-left font-medium">{label}</th>
      <td className="py-1 pr-3">{cell(metric.before)}</td>
      <td className="py-1 pr-3">{cell(metric.after)}</td>
      <td className="py-1">{signedCount(metric.difference)}</td>
    </tr>
  );
}

function EventFormDialog({
  repositoryId,
  event,
  onClose,
  onSaved,
}: {
  repositoryId: number;
  event: GrowthEvent | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [type, setType] = useState(event?.type ?? "CUSTOM");
  const [eventAt, setEventAt] = useState(toDateTimeLocal(event?.eventAt ?? new Date().toISOString()));
  const [title, setTitle] = useState(event?.title ?? "");
  const [url, setUrl] = useState(event?.url ?? "");
  const [description, setDescription] = useState(event?.description ?? "");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function submit() {
    setSaving(true);
    setError(null);
    const body: ManualGrowthEventRequest = {
      type,
      eventAt: fromDateTimeLocal(eventAt),
      title: title.trim(),
      url: url.trim() || undefined,
      description: description.trim() || undefined,
    };
    try {
      if (event) {
        await api(`/api/v1/growth-events/${event.id}`, { method: "PUT", body: JSON.stringify(body) });
      } else {
        await api(`/api/v1/repositories/${repositoryId}/growth-events`, {
          method: "POST",
          body: JSON.stringify(body),
        });
      }
      onSaved();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to save event");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <Card className="w-full max-w-lg" onClick={(click) => click.stopPropagation()}>
        <h2 className="mb-4 font-medium">{event ? "Edit event" : "Add event"}</h2>
        <div className="space-y-3">
          <label className="block text-sm">
            <span className="mb-1 block text-muted-foreground">Type</span>
            <select className="w-full rounded-md border px-3 py-2" value={type} onChange={(change) => setType(change.target.value)}>
              {MANUAL_EVENT_TYPES.map((item) => (
                <option key={item.type} value={item.type}>
                  {item.label}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-muted-foreground">Date / Time</span>
            <input
              type="datetime-local"
              className="w-full rounded-md border px-3 py-2"
              value={eventAt}
              onChange={(change) => setEventAt(change.target.value)}
            />
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-muted-foreground">Title</span>
            <input className="w-full rounded-md border px-3 py-2" value={title} onChange={(change) => setTitle(change.target.value)} />
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-muted-foreground">URL</span>
            <input className="w-full rounded-md border px-3 py-2" value={url} onChange={(change) => setUrl(change.target.value)} />
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-muted-foreground">Description</span>
            <textarea
              className="w-full rounded-md border px-3 py-2"
              rows={3}
              value={description}
              onChange={(change) => setDescription(change.target.value)}
            />
          </label>
          {error && <div className="text-sm text-red-700">{error}</div>}
        </div>
        <div className="mt-4 flex justify-end gap-2">
          <Button type="button" className="bg-muted text-foreground" onClick={onClose}>
            Cancel
          </Button>
          <Button type="button" disabled={saving || !title.trim()} onClick={() => void submit()}>
            {saving ? "Saving..." : "Save"}
          </Button>
        </div>
      </Card>
    </div>
  );
}
