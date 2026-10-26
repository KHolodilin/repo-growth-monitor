import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { api } from "../lib/api";
import { PersistentECharts } from "./PersistentECharts";
import { ChartCard, ChartDownloadMenu, ChartExportProvider, useChartMode, useRegisterChartExport } from "./ChartDownloadMenu";

vi.mock("../lib/api", () => ({ api: vi.fn(() => Promise.resolve([])) }));

function Registrar({ onPng, onCsv }: { onPng: () => void; onCsv: () => void }) {
  useRegisterChartExport({ downloadPng: onPng, downloadCsv: onCsv });
  return null;
}

function Host({ showChart }: { showChart: boolean }) {
  const [onPng] = useState(() => vi.fn());
  const [onCsv] = useState(() => vi.fn());
  return (
    <ChartExportProvider>
      <ChartDownloadMenu />
      {showChart ? <Registrar onPng={onPng} onCsv={onCsv} /> : null}
      <button type="button" onClick={() => onPng.mockClear()}>
        reset
      </button>
      <span data-testid="png-count">{onPng.mock.calls.length}</span>
      <span data-testid="csv-count">{onCsv.mock.calls.length}</span>
    </ChartExportProvider>
  );
}

describe("ChartDownloadMenu", () => {
  it("stays hidden until a chart registers export", async () => {
    const user = userEvent.setup();
    const view = render(<ChartDownloadMenu />);
    expect(view.container).toBeEmptyDOMElement();

    view.rerender(
      <Host showChart={false} />,
    );
    expect(screen.queryByRole("button", { name: "Download" })).not.toBeInTheDocument();

    view.rerender(<Host showChart />);
    const download = await screen.findByRole("button", { name: "Download" });
    expect(download).not.toHaveTextContent("Download");
    expect(download).toHaveClass("rounded-lg", "p-1");
    expect(download.firstElementChild).toHaveClass("min-h-8", "py-1.5");
    expect(screen.getByRole("tooltip", { name: "Download" })).toHaveClass("group-hover:block");
    expect(download).toHaveAttribute("aria-expanded", "false");
    await user.click(download);
    expect(screen.queryByRole("tooltip", { name: "Download" })).not.toBeInTheDocument();
    expect(download).toHaveAttribute("aria-expanded", "true");
    await user.keyboard("a");
    expect(screen.getByRole("menu", { name: "Download format" })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();

    await user.click(download);
    await user.click(document.body);
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();

    await user.click(download);
    await user.click(download);
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "All events" }));
    await user.click(screen.getByRole("menuitemradio", { name: "GitHub events" }));
    await user.click(screen.getByRole("button", { name: "Calendar" }));
    await user.click(screen.getByRole("menuitemradio", { name: "Timeline" }));

    view.rerender(<Host showChart={false} />);
    expect(screen.queryByRole("button", { name: "Download" })).not.toBeInTheDocument();
  });

  it("downloads the chosen format and keeps the title beside the controls", async () => {
    const user = userEvent.setup();
    const onPng = vi.fn();
    const onCsv = vi.fn();
    render(
      <ChartCard title="Traffic" controls={<button type="button">Place</button>}>
        <Registrar onPng={onPng} onCsv={onCsv} />
      </ChartCard>,
    );
    const heading = screen.getByRole("heading", { name: "Traffic" });
    const row = heading.parentElement;
    expect(row?.className).toContain("flex");
    expect(row).toContainElement(screen.getByRole("button", { name: "Place" }));
    expect(row).toContainElement(screen.getByRole("button", { name: "All events" }));
    expect(row).toContainElement(screen.getByRole("button", { name: "Calendar" }));
    expect(row).toContainElement(screen.getByRole("button", { name: "Download" }));
    expect(row?.querySelectorAll(".w-px")).toHaveLength(2);

    await user.click(screen.getByRole("button", { name: "Download" }));
    await user.click(screen.getByRole("menuitem", { name: "PNG" }));
    expect(onPng).toHaveBeenCalledOnce();
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Download" }));
    await user.click(screen.getByRole("menuitem", { name: "CSV" }));
    expect(onCsv).toHaveBeenCalledOnce();
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("filters events and switches the axis from the header menus", async () => {
    const user = userEvent.setup();
    render(
      <ChartCard title="Traffic">
        <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
      </ChartCard>,
    );
    await user.click(screen.getByRole("button", { name: "All events" }));
    expect(screen.getByRole("menuitemradio", { name: "All events" })).toHaveAttribute("aria-checked", "true");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "All events" }));
    await user.click(document.body);
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "All events" }));
    await user.click(screen.getByRole("menuitemradio", { name: "Events off" }));
    expect(screen.getByRole("button", { name: "Events off" })).toBeInTheDocument();
    expect(screen.getByRole("tooltip", { name: "Events off" })).toHaveClass("group-hover:block");

    await user.click(screen.getByRole("button", { name: "Calendar" }));
    await user.click(screen.getByRole("menuitemradio", { name: "Timeline" }));
    expect(screen.getByRole("button", { name: "Timeline" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Timeline" }));
    await user.click(screen.getByRole("button", { name: "Timeline" }));
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("uses the default mode outside a card and reloads fetched events", async () => {
    const user = userEvent.setup();
    function Outside() {
      const mode = useChartMode();
      return (
        <button type="button" onClick={() => mode.openMarker([])}>
          outside
        </button>
      );
    }
    render(<Outside />);
    await user.click(screen.getByRole("button", { name: "outside" }));

    vi.mocked(api).mockRejectedValueOnce(new Error("down"));
    const failed = render(
      <ChartCard title="Traffic" repositoryId={13} onEventsChanged={vi.fn()}>
        <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
      </ChartCard>,
    );
    expect(await screen.findByRole("button", { name: "All events" })).toBeInTheDocument();
    failed.unmount();

    let fail: (error: Error) => void = () => undefined;
    vi.mocked(api).mockImplementation(
      () => new Promise((_resolve, reject) => {
        fail = reject;
      }),
    );
    const late = render(
      <ChartCard title="Traffic" repositoryId={13}>
        <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
      </ChartCard>,
    );
    late.unmount();
    fail(new Error("late"));

    let release: (rows: unknown) => void = () => undefined;
    vi.mocked(api).mockImplementation(
      () => new Promise((resolve) => {
        release = resolve;
      }),
    );
    const view = render(
      <ChartCard title="Traffic" repositoryId={13}>
        <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
        <PersistentECharts
          chartId="events"
          series={[{ key: "Views", name: "Views" }]}
          option={{ xAxis: { data: ["2026-09-22"] }, series: [{ name: "Views", type: "line", data: [1] }] }}
        />
      </ChartCard>,
    );
    view.unmount();
    release([]);

    vi.mocked(api).mockResolvedValue([]);
    render(
      <ChartCard title="Traffic" repositoryId={13}>
        <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
        <PersistentECharts
          chartId="events"
          series={[{ key: "Views", name: "Views" }]}
          option={{ xAxis: { data: ["2026-09-22"] }, series: [{ name: "Views", type: "line", data: [1] }] }}
        />
      </ChartCard>,
    );
    await user.click(await screen.findByRole("button", { name: "mark" }));
    vi.mocked(api).mockResolvedValueOnce(undefined);
    await user.click(screen.getByRole("button", { name: "Delete" }));
    expect(await screen.findByRole("button", { name: "All events" })).toBeInTheDocument();
  });
});
