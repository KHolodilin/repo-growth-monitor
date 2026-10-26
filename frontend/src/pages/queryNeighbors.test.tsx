import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { repository, searchHistory, searchResults } from "../test/fixtures";
import { QueryDetailsPage } from "./QueryDetailsPage";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

describe("query neighbor chart", () => {
  it("adds another repository to the chart and switches the axis", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return Promise.resolve(repository);
      }
      if (url.includes("/history")) {
        return Promise.resolve(searchHistory);
      }
      if (url.includes("/results")) {
        return Promise.resolve(searchResults);
      }
      if (url.includes("/series")) {
        return Promise.resolve({
          series: [
            {
              githubRepositoryId: 1,
              fullName: "other/repo",
              points: [{ date: "2026-09-23", position: 1, stars: 100, forks: 2 }],
            },
            {
              githubRepositoryId: 99,
              fullName: repository.fullName,
              points: [{ date: "2026-09-23", position: 4, stars: 21, forks: 16 }],
            },
          ],
        });
      }
      return Promise.resolve([]);
    });

    render(
      <MemoryRouter initialEntries={["/repositories/13/search-queries/5"]}>
        <Routes>
          <Route path="/repositories/:repositoryId/search-queries/:queryId" element={<QueryDetailsPage />} />
        </Routes>
      </MemoryRouter>,
    );

    const neighbor = await screen.findByRole("checkbox", { name: "Show other/repo on the chart" });
    expect(screen.getByRole("link", { name: "other/repo" })).toHaveAttribute(
      "href",
      "/repositories/13/search-queries/5/competitors/1",
    );
    expect(screen.getByRole("link", { name: repository.fullName })).toHaveAttribute(
      "href",
      `/repositories/13/search-queries/5/competitors/${repository.githubId}`,
    );
    expect(screen.queryByRole("checkbox", { name: `Show ${repository.fullName} on the chart` })).not.toBeInTheDocument();
    await user.click(neighbor);
    expect(neighbor).toBeChecked();
    await user.click(neighbor);
    expect(neighbor).not.toBeChecked();
    await user.click(neighbor);

    const metric = screen.getByRole("group", { name: "Chart metric" });
    expect(within(metric).getByRole("button", { name: "Place" })).toHaveAttribute("aria-pressed", "true");
    await user.click(within(metric).getByRole("button", { name: "Stars" }));
    expect(within(metric).getByRole("button", { name: "Stars" })).toHaveAttribute("aria-pressed", "true");
    await user.click(within(metric).getByRole("button", { name: "Forks" }));
    expect(within(metric).getByRole("button", { name: "Place" })).toHaveAttribute("aria-pressed", "false");

    expect(screen.getByRole("button", { name: "Calendar" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Calendar" }));
    expect(screen.getByRole("menuitemradio", { name: "Calendar" })).toHaveAttribute("aria-checked", "true");
    await user.click(screen.getByRole("menuitemradio", { name: "Timeline" }));
    expect(screen.getByRole("button", { name: "Timeline" })).toBeInTheDocument();
    expect(within(metric).getByRole("button", { name: "Forks" })).toHaveAttribute("aria-pressed", "true");
    await user.click(screen.getByRole("button", { name: "Timeline" }));
    await user.click(screen.getByRole("menuitemradio", { name: "Calendar" }));
    expect(screen.getByRole("button", { name: "Calendar" })).toBeInTheDocument();
  });
});