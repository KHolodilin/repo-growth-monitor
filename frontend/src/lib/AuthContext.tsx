import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { anonymousMe, loadMe, signOut, type Me } from "./auth";

type AuthContextValue = {
  me: Me;
  loading: boolean;
  refresh: () => Promise<Me>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

/** Used when a screen is rendered without AuthProvider (unit tests). */
const TEST_ADMIN: AuthContextValue = {
  me: {
    authenticated: true,
    userId: "0",
    displayName: "Test Admin",
    roles: ["USER", "ADMIN"],
    actions: ["MUTATE"],
    loginUrl: "/login",
    logoutUrl: "/logout",
  },
  loading: false,
  refresh: async () => TEST_ADMIN.me,
  logout: async () => undefined,
};

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me>(anonymousMe);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    try {
      const next = await loadMe();
      setMe(next);
      return next;
    } catch {
      setMe(anonymousMe);
      return anonymousMe;
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const logout = useCallback(async () => {
    try {
      await signOut(me.logoutUrl);
    } finally {
      setMe({ ...anonymousMe, loginUrl: me.loginUrl || anonymousMe.loginUrl });
    }
  }, [me.loginUrl, me.logoutUrl]);

  return <AuthContext.Provider value={{ me, loading, refresh, logout }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  return useContext(AuthContext) ?? TEST_ADMIN;
}
