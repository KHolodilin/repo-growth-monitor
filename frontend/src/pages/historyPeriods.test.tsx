import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TrafficHistoryPage } from "./TrafficHistoryPage";
import { repository, snapshotHistory } from "../test/fixtures";
import { writeStoredRepoPeriod, PERIODS } from "../components/PeriodSelector";
import { periodDates } from "../lib/searchVisibilityKpi";
import * as downloads from "../lib/chartExport";
const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async original => ({ ...await original<typeof import("../lib/api")>(), api }));
vi.mock("echarts-for-react", async () => {
 const React = await import("react");
 return { default: React.forwardRef(({option}: {option: object}, ref) => { React.useImperativeHandle(ref, () => ({getEchartsInstance: () => ({getDataURL: () => "data:image/png;base64,AAAA"})})); return <pre data-testid="history-option">{JSON.stringify(option)}</pre>; }) };
});
const dates=["2026-09-01","2026-10-20","2026-11-05","2026-11-06"];
function data(url: string) {
 const params=new URL(url,"http://local").searchParams;
 const days=periodDates(params.get("period") as typeof PERIODS[number],"2026-11-06");
 const shown=days ? dates.filter(date=>days.includes(date)) : dates;
 return {...snapshotHistory,kind:params.get("kind")==="paths"?"PATHS":"REFERRERS",dates:shown,rows:[{key:"github.com",cells:shown.map(date=>({date,visitors:2,views:20,visitorsDelta:0,viewsDelta:0,firstSeen:false}))}]};
}
function Probe(){return <p data-testid="history-url">{useLocation().search}</p>;}
function mount(query=""){return render(<MemoryRouter initialEntries={[`/repositories/13/traffic/history${query}`]}><Routes><Route path="/repositories/:id/traffic/history" element={<><Probe/><TrafficHistoryPage/></>}/></Routes></MemoryRouter>);}
beforeEach(()=>{vi.useFakeTimers({toFake:["Date"]});vi.setSystemTime(new Date("2026-11-06T12:00:00Z"));writeStoredRepoPeriod("13","90d");api.mockImplementation(async (url:string)=>url.includes("traffic-history")?data(url):url.endsWith("/repositories/13")?repository:[]);});
afterEach(()=>{vi.useRealTimers();vi.unstubAllGlobals();});
describe.each([false,true])("History shared periods mobile=%s",mobile=>{
 it.each(["referrers","paths"])("loads every %s period, keeps metrics and exports its snapshots",async kind=>{
  vi.stubGlobal("matchMedia",vi.fn(()=>({matches:mobile,addEventListener:vi.fn(),removeEventListener:vi.fn()})));
  const user=userEvent.setup();mount(`?kind=${kind}&period=7d`);
  const group=await screen.findByRole("group",{name:"History period"}); await screen.findByTestId("history-option");
  expect(within(group).getAllByRole("button")).toHaveLength(6);
  await user.click(screen.getAllByRole("button",{name:"Views"})[0]);
  for(const period of PERIODS){
   await user.click(within(group).getByRole("button",{name:period}));
   await waitFor(()=>expect(api.mock.calls.some(([url])=>String(url).includes(`kind=${kind}&period=${period}`))).toBe(true));
   await waitFor(()=>expect(screen.getByTestId("history-option").textContent).toContain(JSON.stringify(data(`http://local?kind=${kind}&period=${period}`).dates)));
   expect(within(group).getByRole("button",{name:period})).toHaveAttribute("aria-pressed","true");
   expect(screen.getByTestId("history-option")).toHaveTextContent("snapshot rolling window");
  }
  const csv=vi.spyOn(downloads,"downloadChartCsv").mockImplementation(()=>{});
  await user.click(screen.getByRole("button",{name:mobile?"Chart menu":"Download"}));await user.click(screen.getByRole("menuitem",{name:"CSV"}));
  expect(csv.mock.lastCall?.[1]).toContain("2026-09-01"); expect(csv.mock.lastCall?.[1]).toContain("Views");
  await user.click(screen.getByRole("button",{name:kind==="paths"?"Referrers":"Popular Paths"}));
  await screen.findByTestId("history-option");expect(screen.getByTestId("history-url")).toHaveTextContent("period=all");
 });
});
it.each([["1","1d"],["7","7d"],["14","30d"],["bad","90d"]])("maps legacy days=%s without retaining the API clamp",async(days,period)=>{mount(`?kind=paths&days=${days}`);const group=await screen.findByRole("group",{name:"History period"});expect(within(group).getByRole("button",{name:period})).toHaveAttribute("aria-pressed","true");expect(api.mock.calls.some(([url])=>String(url).includes(`period=${period}`))).toBe(true);});
it("uses repository preference for an invalid period and preserves restricted filtering",async()=>{const user=userEvent.setup();mount("?kind=paths&period=invalid");const group=await screen.findByRole("group",{name:"History period"});expect(within(group).getByRole("button",{name:"90d"})).toHaveAttribute("aria-pressed","true");await user.click(screen.getByRole("checkbox",{name:"Show restricted pages"}));await user.click(within(group).getByRole("button",{name:"1y"}));expect(api.mock.calls.some(([url])=>String(url).includes("period=1y&includeServicePaths=true"))).toBe(true);});

it("uses the saved period for an empty archive and keeps the empty state honest", async()=>{
 api.mockImplementation(async(url:string)=>url.includes("traffic-history")?{...snapshotHistory,dates:[],rows:[]}:url.endsWith("/repositories/13")?repository:[]);
 mount(); const group=await screen.findByRole("group",{name:"History period"});
 expect(within(group).getByRole("button",{name:"90d"})).toHaveAttribute("aria-pressed","true");
 expect(screen.getByText("No snapshots for this period.")).toBeInTheDocument();
});
it("does not apply a late response from an older period", async()=>{
 const original=api.getMockImplementation()!;let release!:(value:unknown)=>void;
 api.mockImplementation((url:string)=>url.includes("traffic-history")&&url.includes("period=30d")?new Promise(resolve=>{release=resolve;}):original(url));
 const user=userEvent.setup();mount("?kind=referrers&period=7d");const group=await screen.findByRole("group",{name:"History period"});
 await user.click(within(group).getByRole("button",{name:"30d"}));await user.click(within(group).getByRole("button",{name:"90d"}));
 await waitFor(()=>expect(screen.getByTestId("history-option").textContent).toContain("2026-09-01"));
 await act(async()=>{release({...snapshotHistory,dates:["1999-01-01"]});});
 expect(screen.getByTestId("history-option")).not.toHaveTextContent("1999-01-01");
});

it("keeps kind and selected period when switching repositories", async()=>{
 const original=api.getMockImplementation()!;
 api.mockImplementation((url:string)=>url.includes("/repositories?refresh")?Promise.resolve([repository]):original(url));
 const user=userEvent.setup();mount("?kind=paths&period=1y");await screen.findByRole("group",{name:"History period"});
 await user.click(screen.getByRole("button",{name:"Switch repository"}));
 await user.click(await screen.findByRole("option",{name:repository.name}));
 expect(screen.getByTestId("history-url")).toHaveTextContent("kind=paths&period=1y");
});
