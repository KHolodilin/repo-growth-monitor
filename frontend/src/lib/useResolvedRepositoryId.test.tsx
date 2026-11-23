import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useNavigate } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { useResolvedRepositoryId } from "./useResolvedRepositoryId";

const resolveRepository = vi.hoisted(() => vi.fn());

vi.mock("./resolveRepository", () => ({
  resolveRepository,
}));

function Probe() {
  const { id, error } = useResolvedRepositoryId();
  const navigate = useNavigate();
  return (
    <div>
      <span data-testid="id">{id ?? "none"}</span>
      <span data-testid="error">{error ?? ""}</span>
      <button type="button" onClick={() => void navigate("/admin/repositories/99")}>
        go-id
      </button>
      <button type="button" onClick={() => void navigate("/KHolodilin/demo")}>
        go-path
      </button>
      <button type="button" onClick={() => void navigate("/missing")}>
        go-missing
      </button>
    </div>
  );
}

describe("useResolvedRepositoryId", () => {
  beforeEach(() => {
    resolveRepository.mockReset();
  });

  it("uses a direct :id param without calling resolveRepository", () => {
    render(
      <MemoryRouter initialEntries={["/admin/repositories/13"]}>
        <Routes>
          <Route path="/admin/repositories/:id" element={<Probe />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByTestId("id")).toHaveTextContent("13");
    expect(resolveRepository).not.toHaveBeenCalled();
  });

  it("resolves owner/repository path params asynchronously", async () => {
    resolveRepository.mockResolvedValue({ id: 42 });
    render(
      <MemoryRouter initialEntries={["/KHolodilin/demo"]}>
        <Routes>
          <Route path="/:owner/:repository" element={<Probe />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByTestId("id")).toHaveTextContent("none");
    await waitFor(() => expect(screen.getByTestId("id")).toHaveTextContent("42"));
    expect(resolveRepository).toHaveBeenCalledWith("KHolodilin", "demo");
  });

  it("records Error and non-Error resolve failures", async () => {
    resolveRepository.mockRejectedValueOnce(new Error("nope"));
    const first = render(
      <MemoryRouter initialEntries={["/KHolodilin/demo"]}>
        <Routes>
          <Route path="/:owner/:repository" element={<Probe />} />
        </Routes>
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByTestId("error")).toHaveTextContent("nope"));
    first.unmount();

    resolveRepository.mockRejectedValueOnce("boom");
    render(
      <MemoryRouter initialEntries={["/KHolodilin/demo"]}>
        <Routes>
          <Route path="/:owner/:repository" element={<Probe />} />
        </Routes>
      </MemoryRouter>,
    );
    await waitFor(() =>
      expect(screen.getByTestId("error")).toHaveTextContent("Failed to resolve repository"),
    );
  });

  it("resets state when the resolve key changes between id, path and missing", async () => {
    const user = userEvent.setup();
    resolveRepository.mockResolvedValue({ id: 7 });
    render(
      <MemoryRouter initialEntries={["/admin/repositories/13"]}>
        <Routes>
          <Route path="/admin/repositories/:id" element={<Probe />} />
          <Route path="/:owner/:repository" element={<Probe />} />
          <Route path="/missing" element={<Probe />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByTestId("id")).toHaveTextContent("13");
    await user.click(screen.getByRole("button", { name: "go-path" }));
    await waitFor(() => expect(screen.getByTestId("id")).toHaveTextContent("7"));
    await user.click(screen.getByRole("button", { name: "go-missing" }));
    expect(screen.getByTestId("id")).toHaveTextContent("none");
    await user.click(screen.getByRole("button", { name: "go-id" }));
    expect(screen.getByTestId("id")).toHaveTextContent("99");
  });
});
