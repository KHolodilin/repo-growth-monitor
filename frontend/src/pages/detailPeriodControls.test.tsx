import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryDetailsPage } from "./QueryDetailsPage";
import { TopicDetailsPage } from "./TopicDetailsPage";
import { CompetitorCardPage } from "./CompetitorCardPage";
import { TopicCompetitorCardPage } from "./TopicCompetitorCardPage";
import { repository, searchHistory, searchResults, topicHistory, topicResults } from "../test/fixtures";
import { writeStoredRepoPeriod } from "../components/PeriodSelector";
import * as exports from "../lib/chartExport";
const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async original => ({ ...await original<typeof import("../lib/api")>(), api }));
vi.mock("echarts-for-react", async () => {
  const React = await import("react");
  return { default: React.forwardRef(function Plot({ option }: { option: object }, ref) {
    React.useImperativeHandle(ref, () => ({ getEchartsInstance: () => ({ getDataURL: () => "data:image/png;base64,AAAA" }) }));
    return <pre data-testid="period-option">{JSON.stringify(option)}</pre>;
  }) };
});
const dates = ["2026-09-01", "2026-11-03", "2026-11-04", "2026-11-05"];
const points = dates.map((date, index) => ({ date, position: index + 1, stars: index === 2 ? 0 : 20, forks: index, searchRunId: 1, topicRunId: 1 }));
const query = { ...searchHistory, points, query: { ...searchHistory.query, id: 5 } };
const topic = { ...topicHistory, points };
const card = { githubRepositoryId: 1, fullName: "other/repo", htmlUrl: "https://github.com/other/repo", description: "Competitor", topics: [], discoverability: [], points };
const routes = {
  query: "/repositories/13/search-queries/5?metric=stars",
  topic: "/repositories/13/topics/outbox?scope=language",
  queryCompetitor: "/repositories/13/search-queries/5/competitors/1",
  topicCompetitor: "/repositories/13/topics/outbox/competitors/1?scope=language",
};
function mount(path: string) { return render(<MemoryRouter initialEntries={[path]}><Routes><Route path="/repositories/:repositoryId/search-queries/:queryId" element={<QueryDetailsPage />} /><Route path="/repositories/:repositoryId/topics/:topic" element={<TopicDetailsPage />} /><Route path="/repositories/:repositoryId/search-queries/:queryId/competitors/:githubRepositoryId" element={<CompetitorCardPage />} /><Route path="/repositories/:repositoryId/topics/:topic/competitors/:githubRepositoryId" element={<TopicCompetitorCardPage />} /></Routes></MemoryRouter>); }
function option() { return JSON.parse(screen.getByTestId("period-option").textContent!) as { xAxis: { data: string[] }; series: { name: string; data: (number|null)[] }[] }; }
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] }); vi.setSystemTime(new Date("2026-11-05T12:00:00Z"));
  writeStoredRepoPeriod("13", "all");
  api.mockReset(); api.mockImplementation(async (url: string) => {
    if (url.endsWith("/repositories/13")) return repository;
    if (url.includes("/competitors/")) return card;
    if (url.includes("/history")) return url.includes("/topics/") ? topic : query;
    if (url.includes("/results")) return url.includes("/topics/") ? topicResults : searchResults;
    if (url.includes("/series")) return url.includes("/topics/") ? [{ githubRepositoryId: 2, fullName: "big/outbox", points }] : { series: [repository.githubId,1].map(githubRepositoryId => ({ githubRepositoryId, fullName: githubRepositoryId === 1 ? "other/repo" : repository.fullName, points })) };
    return [];
  });
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });
describe.each([false,true])("detail period controls (mobile=%s)", mobile => {
  it.each(Object.keys(routes) as (keyof typeof routes)[])("filters %s, exports its window and restores the repository preference", async route => {
    vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: mobile, addEventListener: vi.fn(), removeEventListener: vi.fn() })));
    const user = userEvent.setup(); const view = mount(routes[route]);
    const group = await screen.findByRole("group", { name: "History period" });
    await screen.findByTestId("period-option");
    await waitFor(() => expect(within(group).getByRole("button", { name: "all", pressed: true })).toBeInTheDocument());
    const competitorName = route === "query" ? "other/repo" : "big/outbox";
    if (route === "query" || route === "topic") await user.click(await screen.findByRole("checkbox", { name: `Show ${competitorName} on the chart` }));
    await user.click(within(group).getByRole("button", { name: "1d" }));
    await waitFor(() => expect(option().xAxis.data).toEqual(["2026-11-04"]));
    expect(within(group).getByRole("button", { name: "1d", pressed: true })).toBeInTheDocument();
    if (route === "query" || route === "topic") expect(screen.getByRole("checkbox", { name: `Show ${competitorName} on the chart` })).toBeChecked();
    if (route !== "topic") expect(option().series[0].data).toEqual([0]);
    const csv = vi.spyOn(exports,"downloadChartCsv").mockImplementation(() => {});
    await user.click(screen.getByRole("button", { name: mobile ? "Chart menu" : "Download" }));
    await user.click(screen.getByRole("menuitem", { name: "CSV" }));
    expect(csv.mock.lastCall?.[1]).toContain("2026-11-04"); expect(csv.mock.lastCall?.[1]).not.toContain("2026-09-01");
    expect(api.mock.calls.some(([url]) => String(url).includes("growth-events?period=1d"))).toBe(true);
    await user.click(within(group).getByRole("button", { name: "7d" }));
    await waitFor(() => expect(option().xAxis.data[0]).toBe("2026-10-30"));
    await user.click(within(group).getByRole("button", { name: "all" }));
    await waitFor(() => expect(option().xAxis.data[0]).toBe("2026-09-01"));
    await user.click(within(group).getByRole("button", { name: "1d" }));
    view.unmount(); mount(routes[route]);
    await screen.findByTestId("period-option");
    await waitFor(() => expect(option().xAxis.data).toEqual(["2026-11-04"]));
    expect(screen.getByRole("button", { name: "1d", pressed: true })).toBeInTheDocument();
  });
});
