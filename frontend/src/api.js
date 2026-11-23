import axios from 'axios';

/**
 * Thin API client for the JobWatch REST endpoints. In dev, Vite proxies `/api`
 * to the Spring Boot app; in prod the SPA is served from the same origin.
 */
const envBase = (import.meta.env.VITE_API_BASE_URL || '').trim();
const normalizedBase = envBase.replace(/\/$/, '');
const apiBase = normalizedBase ? `${normalizedBase}/api` : '/api';
const http = axios.create({ baseURL: apiBase, timeout: 20000 });

// ── Session token ───────────────────────────────────────────────────────────
// The backend issues an opaque bearer token at login/sign-up. It is kept in localStorage
// (the API lives on a different origin, so cookies are not an option) and sent on every call.
const TOKEN_KEY = 'jw_token';
export const getToken = () => {
  try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
};
export const setToken = (token) => {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch { /* storage unavailable: session lasts for this tab only */ }
};

/** Fired when the backend rejects the token (expired / signed out elsewhere). */
export const AUTH_EXPIRED_EVENT = 'jw:auth-expired';

/** Human-readable message from an API error (server message when present). */
export const errorMessage = (err, fallback = 'Something went wrong') =>
  err?.response?.data?.message || err?.response?.data?.error || fallback;

// ── Cold-start resilience ─────────────────────────────────────────────────────
// The backend (Fly) can be cold/booting on first load, returning 502/503/504 or a
// network error (no CORS headers on the proxy error page). Instead of surfacing a
// confusing CORS/"failed to load" error, transparently retry a few times with
// exponential backoff so the first page load self-heals once the app is up.
const MAX_RETRIES = 4;
const RETRYABLE_STATUS = new Set([429, 502, 503, 504]);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Lightweight console logging to help debug prod (enable/disable via VITE_DEBUG_API).
const DEBUG = String(import.meta.env.VITE_DEBUG_API ?? 'true') !== 'false';
const log = (...args) => { if (DEBUG) console.log('[api]', ...args); };

http.interceptors.request.use((config) => {
  config.__start = Date.now();
  const token = getToken();
  if (token) config.headers = { ...config.headers, Authorization: `Bearer ${token}` };
  log('→', (config.method || 'get').toUpperCase(), config.baseURL + config.url,
    config.params ? { params: config.params } : '');
  return config;
});

http.interceptors.response.use(
  (response) => {
    const ms = response.config.__start ? Date.now() - response.config.__start : '?';
    const d = response.data;
    const summary = d && typeof d === 'object'
      ? { total: d.total, jobs: Array.isArray(d.jobs) ? d.jobs.length : undefined,
          profiles: Array.isArray(d.profiles) ? d.profiles.length : undefined,
          profileFacet: Array.isArray(d.profileFacet) ? d.profileFacet.length : undefined }
      : d;
    log('←', response.status, response.config.url, `${ms}ms`, summary);
    return response;
  },
  async (error) => {
    const config = error.config;
    if (!config) {
      log('✗ no-config error', error.message);
      return Promise.reject(error);
    }
    // No response at all (network/CORS-on-error) or a retryable gateway status.
    const status = error.response?.status;
    if (status === 401 && !String(config.url || '').startsWith('/auth/')) {
      setToken(null);
      window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
      return Promise.reject(error);
    }
    const isRetryable = status == null || RETRYABLE_STATUS.has(status);
    config.__retryCount = config.__retryCount || 0;

    if (isRetryable && config.__retryCount < MAX_RETRIES) {
      config.__retryCount += 1;
      // 0.8s, 1.6s, 3.2s, 6.4s (+ jitter) — covers typical JVM cold-start windows.
      const backoff = 800 * 2 ** (config.__retryCount - 1);
      const jitter = Math.random() * 300;
      log(`⟳ retry ${config.__retryCount}/${MAX_RETRIES} after ${Math.round(backoff)}ms`,
        config.url, 'status=', status ?? 'network/CORS');
      await sleep(backoff + jitter);
      return http(config);
    }
    log('✗ giving up', config.url, 'status=', status ?? 'network/CORS', error.message);
    return Promise.reject(error);
  },
);

// ── Account ─────────────────────────────────────────────────────────────────
export const signUp = (payload) => http.post('/auth/signup', payload).then((r) => r.data);
export const logIn = (payload) => http.post('/auth/login', payload).then((r) => r.data);
export const logOut = () => http.post('/auth/logout').then(() => undefined);
export const getMe = () => http.get('/auth/me').then((r) => r.data);
export const updateMe = (payload) => http.put('/auth/me', payload).then((r) => r.data);
export const changePassword = (payload) => http.post('/auth/password', payload).then((r) => r.data);

// ── Profiles ────────────────────────────────────────────────────────────────
export const getProfiles = () => http.get('/profiles').then((r) => r.data);
export const getProfile = (id) => http.get(`/profiles/${id}`).then((r) => r.data);
export const createProfile = (payload) => http.post('/profiles', payload).then((r) => r.data);
export const updateProfile = (id, payload) =>
  http.put(`/profiles/${id}`, payload).then((r) => r.data);
export const deleteProfile = (id) => http.delete(`/profiles/${id}`).then((r) => r.data);
export const toggleActive = (id) =>
  http.post(`/profiles/${id}/toggle-active`).then((r) => r.data);

// ── Analytics & jobs ────────────────────────────────────────────────────────
export const getAnalytics = () => http.get('/analytics').then((r) => r.data);
export const getJobs = (params) => http.get('/jobs', { params }).then((r) => r.data);
export const runJobsReload = () => http.post('/jobs/reload').then((r) => r.data);
export const getJobsReloadStatus = () => http.get('/jobs/reload/status').then((r) => r.data);
export const setApplied = (id, applied) =>
  http.post(`/jobs/${id}/applied`, null, { params: { applied } }).then((r) => r.data);

export default http;

