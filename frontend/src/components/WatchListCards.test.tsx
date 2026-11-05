import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { WatchListCards } from "./WatchListCards";
const row = { id: 1, name: "a very long search query language:Java", href: "/query/1", seriesKey: "query:1", checked: true, onToggle: vi.fn(), rank: 1, limit: 50, change: { kind: "NONE" as const, amount: 0, rank: 1 }, change7d: 0, change30d: null, best: 1, total: 0, updated: "2026-11-05T10:00:00Z" };
describe("WatchListCards", () => {
  it("keeps names, complete metrics and independent chart selection accessible", async () => {
    const user = userEvent.setup(); const toggle = vi.fn(); const onToggleAll = vi.fn(); const onToggle = vi.fn();
    const view = render(<MemoryRouter><WatchListCards nameLabel="Search Query" rows={[{ ...row, onToggle, actions: <button>Run</button> }, { ...row, id: 2, name: "missing", checked: false, rank: null, total: null, updated: null }]} sortKey={null} sortDir="asc" toggle={toggle} allChecked={false} onToggleAll={onToggleAll} /></MemoryRouter>);
    expect(screen.getByRole("link", { name: row.name })).toHaveAttribute("href", row.href);
    expect(screen.getByRole("checkbox", { name: "Chart lines" })).toBePartiallyChecked();
    expect(screen.getByRole("button", { name: "Reverse sort direction" })).toBeDisabled();
    const card = screen.getByRole("link", { name: row.name }).closest("article")!;
    expect(within(card).getAllByText("#1")).toHaveLength(2);
    expect(within(card).getAllByText("0").length).toBeGreaterThan(0);
    expect(within(card).getByText(/Nov 5/)).toBeInTheDocument();
    expect(within(card).getByRole("button", { name: "Run" })).toBeInTheDocument();
    await user.click(screen.getByRole("checkbox", { name: `Chart line for ${row.name}` })); expect(onToggle).toHaveBeenCalledOnce();
    await user.click(screen.getByRole("checkbox", { name: "Chart lines" })); expect(onToggleAll).toHaveBeenCalledOnce();
    await user.selectOptions(screen.getByRole("combobox", { name: "Sort by" }), "updated"); expect(toggle).toHaveBeenLastCalledWith("updated");
    view.rerender(<MemoryRouter><WatchListCards nameLabel="Topic" rows={[row]} sortKey="updated" sortDir="desc" toggle={toggle} allChecked onToggleAll={onToggleAll} /></MemoryRouter>);
    expect(screen.getByRole("checkbox", { name: "Chart lines" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Chart lines" })).not.toBePartiallyChecked();
    await user.click(screen.getByRole("button", { name: "Reverse sort direction" })); expect(toggle).toHaveBeenLastCalledWith("updated");
    expect(screen.getByRole("button", { name: "Reverse sort direction" })).toHaveTextContent("↓");
  });
});
