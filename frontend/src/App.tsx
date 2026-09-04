import { Navigate, Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { DashboardPage } from "./pages/DashboardPage";
import { RepositoriesPage } from "./pages/RepositoriesPage";
import { RepositoryDetailsPage } from "./pages/RepositoryDetailsPage";
import { QueryDetailsPage } from "./pages/QueryDetailsPage";
import { SearchResultsPage } from "./pages/SearchResultsPage";
import { TrafficHistoryPage } from "./pages/TrafficHistoryPage";

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/repositories" element={<RepositoriesPage />} />
        <Route path="/repositories/:id" element={<RepositoryDetailsPage />} />
        <Route path="/repositories/:id/traffic/history" element={<TrafficHistoryPage />} />
        <Route path="/repositories/:repositoryId/search-queries/:queryId" element={<QueryDetailsPage />} />
        <Route path="/search-runs/:id" element={<SearchResultsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
