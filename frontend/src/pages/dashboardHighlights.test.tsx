import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { dashboardReady, repository, traffic } from "../test/fixtures";
import { DashboardPage } from "./DashboardPage";
import { RepositoryDetailsPage } from "./RepositoryDetailsPage";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

describe("portfolio highlights", () => {
  it("links the period lines and colors discoverability", async () => {
    const user = userEvent.setup();
    api.mockResolvedValue({
      ...dashboardReady,
      highlights: {
        rankRise: { repositoryId: 13, fullName: "acme/outbox", queryName: "outbox", fromRank: 22, toRank: 14 },
        rankDrop: { repositoryId: 8, fullName: "acme/kafka", queryName: "kafka", fromRank: 4, toRank: 11 },
        events: { count: 2, repositoryId: 13, fullName: "acme/outbox", repositoryNames: ["acme/outbox"] },
        visitors: { repositoryId: 13, fullName: "acme/outbox", growthPercent: 25 },
      },
      repositories: [
        { ...dashboardReady.repositories[0], discoverabilityPassed: 12, discoverabilityTotal: 13 },
        { ...dashboardReady.repositories[0], id: 8, fullName: "acme/mid", visitors: 2, discoverabilityPassed: 8, discoverabilityTotal: 13 },
        { ...dashboardReady.repositories[0], id: 9, fullName: "acme/low", visitors: 1, discoverabilityPassed: 3, discoverabilityTotal: 13 },
        { ...dashboardReady.repositories[0], id: 10, fullName: "acme/none", visitors: 0, discoverabilityPassed: null, discoverabilityTotal: null },
      ],
    });
    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    expect(await screen.findByRole("link", { name: "acme/outbox · outbox 22 → 14" })).toHaveAttribute(
      "href",
      "/repositories/13?tab=search",
    );
    expect(screen.getByRole("link", { name: "acme/kafka · kafka 4 → 11" })).toHaveAttribute(
      "href",
      "/repositories/8?tab=search",
    );
    expect(screen.getByRole("link", { name: "2 promotion events · acme/outbox" })).toHaveAttribute(
      "href",
      "/repositories/13?tab=growth-events",
    );
    expect(screen.getByRole("link", { name: /acme\/outbox ↑ 25%/ })).toHaveAttribute("href", "/repositories/13");
    expect(screen.getByText("12")).toHaveClass("text-emerald-700");
    expect(screen.getByText("8")).toHaveClass("text-amber-600");
    expect(screen.getByText("3")).toHaveClass("text-red-700");
    expect(screen.getByRole("link", { name: "12/13" })).toHaveAttribute(
      "href",
      "/repositories/13?tab=overview&focus=health",
    );
    const dash = screen.getAllByText("—").find((node) => node.closest("td"));
    expect(dash?.className ?? "").not.toMatch(/emerald|amber|red/);
    expect(screen.getByRole("heading", { name: "Repositories" }).parentElement).not.toHaveTextContent("checks");
    await user.click(screen.getByRole("link", { name: "12/13" }));
    await user.click(screen.getByRole("button", { name: /Disc\./ }));
  });

  it("covers a drop without a rise, one promotion event, and a visitor percent that cannot be shown", async () => {
    api.mockResolvedValue({
      ...dashboardReady,
      highlights: {
        rankRise: null,
        rankDrop: { repositoryId: 8, fullName: "acme/kafka", queryName: "kafka", fromRank: 4, toRank: 11 },
        events: { count: 1, repositoryId: 8, fullName: "acme/kafka", repositoryNames: ["acme/kafka", "acme/outbox"] },
        visitors: { repositoryId: 13, fullName: "acme/outbox", growthPercent: Number.NaN },
      },
    });
    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    expect(await screen.findByRole("link", { name: "acme/kafka · kafka 4 → 11" })).toHaveAttribute(
      "href",
      "/repositories/8?tab=search",
    );
    expect(screen.queryByText("No rank changes")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "1 promotion event · acme/kafka, acme/outbox" })).toHaveAttribute(
      "href",
      "/repositories/8?tab=growth-events",
    );
    expect(screen.getByText("No visitor growth")).toBeInTheDocument();
  });

  it("scrolls overview to repository health from the portfolio link", async () => {
    vi.mocked(Element.prototype.scrollIntoView).mockClear();
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return Promise.resolve(repository);
      }
      if (url.includes("/traffic?")) {
        return Promise.resolve(traffic);
      }
      return Promise.resolve([]);
    });
    render(
      <MemoryRouter initialEntries={["/repositories/13?tab=overview&focus=health"]}>
        <Routes>
          <Route path="/repositories/:id" element={<RepositoryDetailsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByRole("heading", { name: "Discoverability" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Repository Health" })).not.toBeInTheDocument();
    await waitFor(() => {
      expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
    });
  });
});
