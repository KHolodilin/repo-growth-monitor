import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { repository, searchHistory, topicHistory, traffic } from "../test/fixtures";
import { RepositoryDetailsPage } from "./RepositoryDetailsPage";
const state = vi.hoisted(() => ({ api: vi.fn(), admin: false, busy: false }));
vi.mock("../lib/api", async original => ({ ...await original<typeof import("../lib/api")>(), api: state.api }));
vi.mock("../lib/mutationAccess", () => ({ useMutationAccess: () => ({ allowMutations: state.admin }) }));
function Destination() { const location = useLocation(); return <p data-testid="destination">{location.pathname}{location.search}</p>; }
function mount(tab: string) { return render(<MemoryRouter initialEntries={[`/repositories/13?tab=${tab}&scope=all`]}><Routes><Route path="/repositories/:id" element={<RepositoryDetailsPage />} /><Route path="/repositories/:id/search-queries/:queryId" element={<Destination />} /><Route path="/repositories/:id/topics/:topic" element={<Destination />} /></Routes></MemoryRouter>); }
beforeEach(() => {
  state.admin = false; state.busy = false; state.api.mockReset();
  vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() })));
  state.api.mockImplementation(async (url: string) => {
    if (url.endsWith("/repositories/13")) return { ...repository, language: "Java" };
    if (url.includes("/traffic?")) return traffic;
    if (url.includes("/search-visibility")) return [{ ...searchHistory, searchStatus: state.busy ? "RUNNING" : undefined }, { ...searchHistory, query: { ...searchHistory.query, id: 6, name: "zeta" }, currentRank: 8 }];
    if (url.includes("/topics-visibility")) return url.includes("scope=language") ? [{ ...topicHistory, watch: { ...topicHistory.watch, language: "Java" }, searchStatus: state.busy ? "RUNNING" : undefined }] : [{ ...topicHistory, searchStatus: state.busy ? "RUNNING" : undefined }, { ...topicHistory, watch: { ...topicHistory.watch, id: 8, topic: "hidden" }, currentRank: 26 }];
    return [];
  });
});
afterEach(() => vi.unstubAllGlobals());
describe("mobile repository watch-lists", () => {
  it("preserves query selection, select-all and sorting while keeping links independent", async () => {
    const user = userEvent.setup(); mount("search");
    const name = searchHistory.query.name;
    await screen.findByLabelText("Search Query watch-list");
    await waitFor(() => expect(screen.getByRole("checkbox", { name: `Chart line for ${name}` })).toBeChecked());
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.queryByRole("button", { name: "Run" })).toBeNull();
    await user.click(screen.getByRole("checkbox", { name: `Chart line for ${name}` }));
    expect(screen.getByRole("checkbox", { name: "Chart lines" })).toBePartiallyChecked();
    await user.click(screen.getByRole("checkbox", { name: "Chart lines" }));
    await user.click(screen.getByRole("checkbox", { name: "Chart lines" }));
    expect(screen.getByRole("checkbox", { name: `Chart line for ${name}` })).not.toBeChecked();
    await user.click(screen.getByRole("checkbox", { name: `Chart line for ${name}` }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Sort by" }), "name");
    await user.click(screen.getByRole("button", { name: "Reverse sort direction" }));
    expect(screen.getAllByRole("article")[0]).toHaveTextContent("zeta");
    await user.click(screen.getByRole("button", { name: "30d" }));
    await waitFor(() => expect(screen.getByRole("checkbox", { name: `Chart line for ${name}` })).toBeChecked());
    await user.click(screen.getByRole("link", { name }));
    expect(await screen.findByTestId("destination")).toHaveTextContent("/repositories/13/search-queries/5");
  });
  it("keeps hidden topic selections when selecting all within language scope", async () => {
    const user = userEvent.setup(); mount("topics");
    await screen.findByLabelText("Topic watch-list");
    await waitFor(() => expect(screen.getByRole("checkbox", { name: "Chart line for outbox" })).toBeChecked());
    expect(screen.getByRole("checkbox", { name: "Chart line for hidden" })).toBeChecked();
    await user.click(screen.getByRole("button", { name: "Java" }));
    await waitFor(() => expect(screen.queryByRole("checkbox", { name: "Chart line for hidden" })).toBeNull());
    await user.click(screen.getByRole("checkbox", { name: "Chart lines" }));
    expect(screen.getByRole("checkbox", { name: "Chart line for outbox" })).not.toBeChecked();
    await user.click(screen.getByRole("checkbox", { name: "Chart lines" }));
    await user.click(screen.getByRole("checkbox", { name: "Chart line for outbox" }));
    await user.click(screen.getByRole("checkbox", { name: "Chart line for outbox" }));
    await user.click(screen.getByRole("button", { name: "All" }));
    expect(await screen.findByRole("checkbox", { name: "Chart line for hidden" })).toBeChecked();
    await user.click(screen.getByRole("link", { name: "outbox" }));
    expect(await screen.findByTestId("destination")).toHaveTextContent("/repositories/13/topics/outbox");
  });
  it.each(["search", "topics"])("retains admin run actions on %s cards", async tab => {
    state.admin = true; const user = userEvent.setup(); const view = mount(tab);
    await screen.findByLabelText(tab === "search" ? "Search Query watch-list" : "Topic watch-list");
    const card = screen.getByRole("link", { name: tab === "search" ? searchHistory.query.name : "outbox" }).closest("article")!;
    const run = within(card).getByRole("button", { name: "Run" });
    let complete: (value: unknown) => void = () => {};
    const base = state.api.getMockImplementation()!;
    state.api.mockImplementation((url: string, options?: { method?: string }) => options?.method === "POST" ? new Promise(resolve => { complete = resolve; }) : base(url));
    await user.click(run);
    expect(await screen.findByRole("button", { name: "Running..." })).toBeDisabled();
    await act(async () => complete([]));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Running..." })).toBeNull());
    if (tab === "search") {
      await user.click(within(screen.getByRole("link", { name: searchHistory.query.name }).closest("article")!).getByRole("button", { name: "Delete" }));
      expect(state.api).toHaveBeenCalledWith("/api/v1/admin/search-queries/5", { method: "DELETE" });
    }
    view.unmount(); state.busy = true;
    mount(tab);
    await screen.findByLabelText(tab === "search" ? "Search Query watch-list" : "Topic watch-list");
    expect(await screen.findByRole("button", { name: "Running..." })).toBeDisabled();
  });
});
