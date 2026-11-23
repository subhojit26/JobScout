import { Suspense, lazy } from 'react';
import { Routes, Route, Navigate, useLocation } from 'react-router-dom';
import Layout from './components/Layout.jsx';
import { useAuth } from './auth.jsx';

const ProfilesPage = lazy(() => import('./pages/ProfilesPage.jsx'));
const ProfileFormPage = lazy(() => import('./pages/ProfileFormPage.jsx'));
const AnalyticsPage = lazy(() => import('./pages/AnalyticsPage.jsx'));
const JobsPage = lazy(() => import('./pages/JobsPage.jsx'));
const AccountPage = lazy(() => import('./pages/AccountPage.jsx'));
const AuthPage = lazy(() => import('./pages/AuthPage.jsx'));

const loadingFallback = (
  <div style={{ padding: '2rem', color: '#94a3b8', fontSize: '0.95rem' }}>
    Loading…
  </div>
);

/** Signed-in area: the dashboard chrome plus its pages. */
function Dashboard() {
  return (
    <Layout>
      <Suspense fallback={loadingFallback}>
        <Routes>
          <Route path="/" element={<Navigate to="/profiles" replace />} />
          <Route path="/profiles" element={<ProfilesPage />} />
          <Route path="/profiles/new" element={<ProfileFormPage />} />
          <Route path="/profiles/:id/edit" element={<ProfileFormPage />} />
          <Route path="/analytics" element={<AnalyticsPage />} />
          <Route path="/jobs" element={<JobsPage />} />
          <Route path="/account" element={<AccountPage />} />
          <Route path="*" element={<Navigate to="/profiles" replace />} />
        </Routes>
      </Suspense>
    </Layout>
  );
}

export default function App() {
  const { user } = useAuth();
  const location = useLocation();

  if (user === undefined) return loadingFallback; // checking the saved session

  return (
    <Suspense fallback={loadingFallback}>
      <Routes>
        <Route path="/login" element={user ? <Navigate to="/profiles" replace /> : <AuthPage mode="login" />} />
        <Route path="/signup" element={user ? <Navigate to="/profiles" replace /> : <AuthPage mode="signup" />} />
        <Route
          path="*"
          element={user
            ? <Dashboard />
            : <Navigate to="/login" replace state={{ from: location.pathname }} />}
        />
      </Routes>
    </Suspense>
  );
}
