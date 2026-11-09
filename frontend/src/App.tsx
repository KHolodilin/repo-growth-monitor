import { Navigate, Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { RepositoriesPage } from "./pages/RepositoriesPage";
import { RepositoryDetailsPage } from "./pages/RepositoryDetailsPage";
import { QueryDetailsPage } from "./pages/QueryDetailsPage";
import { CompetitorCardPage } from "./pages/CompetitorCardPage";
import { TopicDetailsPage } from "./pages/TopicDetailsPage";
import { TopicCompetitorCardPage } from "./pages/TopicCompetitorCardPage";
import { SearchResultsPage } from "./pages/SearchResultsPage";
import { TrafficHistoryPage } from "./pages/TrafficHistoryPage";

import { AuthProvider, useAuth } from "./lib/AuthContext";
import { PublicLayout, PublicNotFound, PublicHomePlaceholder } from "./public/PublicLayout";
import { PublicScopePage } from "./public/PublicScopePage";
import { PublicAnalyticsPage } from "./public/PublicAnalyticsPage";
function AdminGate() {
 const {me,loading}=useAuth();
 if(loading)return <p role="status">Checking access…</p>;
 if(!me.authenticated||!me.roles.includes("ADMIN"))return <section role="alert"><h1>Admin access required</h1><a href={me.loginUrl}>Sign in</a></section>;
 return <Layout/>;
}
export default function App() {
  return (
    <Routes>
      <Route element={<PublicLayout/>}>
        <Route path="/" element={<PublicHomePlaceholder/>}/>
        <Route path="/:owner" element={<PublicScopePage/>}/>
        <Route path="/:owner/:repository" element={<PublicScopePage/>}/>
        <Route path="/:owner/:repository/analytics/:kind" element={<PublicAnalyticsPage/>}/>
        <Route path="/:owner/:repository/analytics/:kind/:definitionId" element={<PublicAnalyticsPage/>}/>
        <Route path="/:owner/:repository/analytics/:kind/:definitionId/competitors/:githubId" element={<PublicAnalyticsPage/>}/>
        <Route path="*" element={<PublicNotFound/>}/>
      </Route>
      <Route element={<AuthProvider><AdminGate/></AuthProvider>}>
        <Route path="/admin" element={<Navigate to="/admin/repositories" replace />} />
        <Route path="/admin/repositories" element={<RepositoriesPage />} />
        <Route path="/admin/repositories/:id" element={<RepositoryDetailsPage />} />
        <Route path="/admin/repositories/:id/traffic/history" element={<TrafficHistoryPage />} />
        <Route path="/admin/repositories/:repositoryId/search-queries/:queryId" element={<QueryDetailsPage />} />
        <Route
          path="/admin/repositories/:repositoryId/search-queries/:queryId/competitors/:githubRepositoryId"
          element={<CompetitorCardPage />}
        />
        <Route path="/admin/repositories/:repositoryId/topics/:topic" element={<TopicDetailsPage />} />
        <Route
          path="/admin/repositories/:repositoryId/topics/:topic/competitors/:githubRepositoryId"
          element={<TopicCompetitorCardPage />}
        />
        <Route path="/admin/search-runs/:id" element={<SearchResultsPage />} />
      </Route>
    </Routes>
  );
}
