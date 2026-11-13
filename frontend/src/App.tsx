import { Navigate, Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { DashboardPage } from "./pages/DashboardPage";
import { RepositoriesPage } from "./pages/RepositoriesPage";
import { RepositoryDetailsPage } from "./pages/RepositoryDetailsPage";
import { QueryDetailsPage } from "./pages/QueryDetailsPage";
import { CompetitorCardPage } from "./pages/CompetitorCardPage";
import { TopicDetailsPage } from "./pages/TopicDetailsPage";
import { TopicCompetitorCardPage } from "./pages/TopicCompetitorCardPage";
import { SearchResultsPage } from "./pages/SearchResultsPage";
import { TrafficHistoryPage } from "./pages/TrafficHistoryPage";
import { AuthProvider, useAuth } from "./lib/AuthContext";
import { PublicLayout, PublicNotFound } from "./public/PublicLayout";
import { PublicHomePage } from "./public/PublicHomePage";
import { PublicScopePage } from "./public/PublicScopePage";

function Shell() {
  const { me, loading } = useAuth();
  if (loading) {
    return <p role="status">Checking access…</p>;
  }
  if (me.authenticated && me.roles.includes("ADMIN")) {
    return <Layout />;
  }
  return <PublicLayout />;
}

function AdminOnly({ children }: { children: React.ReactNode }) {
  const { me, loading } = useAuth();
  if (loading) {
    return <p role="status">Checking access…</p>;
  }
  if (!me.authenticated || !me.roles.includes("ADMIN")) {
    return (
      <section role="alert">
        <h1>Admin access required</h1>
        <a href={me.loginUrl}>Sign in</a>
      </section>
    );
  }
  return children;
}

/** Admin home is the Portfolio table (as on main); anonymous keeps the public owners directory. */
function HomePage() {
  const { me, loading } = useAuth();
  if (loading) {
    return <p role="status">Checking access…</p>;
  }
  if (me.authenticated && me.roles.includes("ADMIN")) {
    return <DashboardPage />;
  }
  return <PublicHomePage />;
}

/** Admin Portfolio (Disc./Activity) like main; anonymous keeps the public owner dashboard. */
function OwnerPortfolioPage() {
  const { me, loading } = useAuth();
  if (loading) {
    return <p role="status">Checking access…</p>;
  }
  if (me.authenticated && me.roles.includes("ADMIN")) {
    return <DashboardPage />;
  }
  return <PublicScopePage />;
}

export default function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route element={<Shell />}>
          <Route path="/" element={<HomePage />} />
          <Route
            path="/admin/repositories"
            element={
              <AdminOnly>
                <RepositoriesPage />
              </AdminOnly>
            }
          />
          <Route path="/admin" element={<Navigate to="/admin/repositories" replace />} />
          <Route path="/admin/search-runs/:id" element={<AdminOnly><SearchResultsPage /></AdminOnly>} />
          <Route path="/:owner" element={<OwnerPortfolioPage />} />
          <Route path="/:owner/:repository" element={<RepositoryDetailsPage />} />
          <Route path="/:owner/:repository/traffic/history" element={<TrafficHistoryPage />} />
          <Route path="/:owner/:repository/search-queries/:queryId" element={<QueryDetailsPage />} />
          <Route
            path="/:owner/:repository/search-queries/:queryId/competitors/:githubRepositoryId"
            element={<CompetitorCardPage />}
          />
          <Route path="/:owner/:repository/topics/:topic" element={<TopicDetailsPage />} />
          <Route
            path="/:owner/:repository/topics/:topic/competitors/:githubRepositoryId"
            element={<TopicCompetitorCardPage />}
          />
          <Route path="*" element={<PublicNotFound />} />
        </Route>
      </Routes>
    </AuthProvider>
  );
}
