import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { ChartCard, ChartDownloadMenu, ChartExportProvider, useRegisterChartExport } from "./ChartDownloadMenu";

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
    expect(screen.getByRole("tooltip", { name: "Download" })).toHaveClass("group-hover:block");
    expect(download).toHaveAttribute("aria-expanded", "false");
    await user.click(download);
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
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

    view.rerender(<Host showChart={false} />);
    expect(screen.queryByRole("button", { name: "Download" })).not.toBeInTheDocument();
  });

  it("downloads the chosen format and keeps the title beside the controls", async () => {
    const user = userEvent.setup();
    const onPng = vi.fn();
    const onCsv = vi.fn();
    render(
      <ChartCard title="Traffic" controls={<button type="button">All</button>}>
        <Registrar onPng={onPng} onCsv={onCsv} />
      </ChartCard>,
    );
    const heading = screen.getByRole("heading", { name: "Traffic" });
    const row = heading.parentElement;
    expect(row?.className).toContain("flex");
    expect(row).toContainElement(screen.getByRole("button", { name: "All" }));
    expect(row).toContainElement(screen.getByRole("button", { name: "Download" }));

    await user.click(screen.getByRole("button", { name: "Download" }));
    await user.click(screen.getByRole("menuitem", { name: "PNG" }));
    expect(onPng).toHaveBeenCalledOnce();
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Download" }));
    await user.click(screen.getByRole("menuitem", { name: "CSV" }));
    expect(onCsv).toHaveBeenCalledOnce();
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });
});
