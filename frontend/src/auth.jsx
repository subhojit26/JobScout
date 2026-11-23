import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import {
  AUTH_EXPIRED_EVENT, getMe, getToken, logIn, logOut, setToken, signUp, updateMe,
} from './api.js';

const AuthContext = createContext(null);

/**
 * Holds the signed-in user. `user` is undefined while the stored token is being checked,
 * null when signed out, and the account object when signed in.
 */
export function AuthProvider({ children }) {
  const [user, setUser] = useState(getToken() ? undefined : null);

  useEffect(() => {
    if (!getToken()) return;
    getMe().then(setUser).catch(() => { setToken(null); setUser(null); });
  }, []);

  useEffect(() => {
    const onExpired = () => setUser(null);
    window.addEventListener(AUTH_EXPIRED_EVENT, onExpired);
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, onExpired);
  }, []);

  const startSession = useCallback((session) => {
    setToken(session.token);
    setUser(session.user);
    return session.user;
  }, []);

  const value = useMemo(() => ({
    user,
    login: (email, password) => logIn({ email, password }).then(startSession),
    signup: (name, email, password) => signUp({ name, email, password }).then(startSession),
    logout: async () => {
      try { await logOut(); } catch { /* token may already be gone */ }
      setToken(null);
      setUser(null);
    },
    updateSettings: (payload) => updateMe(payload).then(setUser),
    replaceSession: startSession,
  }), [user, startSession]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
