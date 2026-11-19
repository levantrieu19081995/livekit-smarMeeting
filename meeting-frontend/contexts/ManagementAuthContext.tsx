'use client';

import { createContext, useEffect, useRef, useState } from 'react';
import { SessionRequestGate, isRejectedSession } from '@/services/management/sessionRecovery';
import {
  getManagementAuthMe,
  logoutManagementAuth,
} from '@/services/management/managementAuthService';
import type { ManagementAuthUser } from '@/types/management/auth';

interface ManagementAuthContextValue {
  loading: boolean;
  authenticated: boolean;
  user: ManagementAuthUser | null;
  refreshSession: () => Promise<void>;
  logout: () => Promise<void>;
  sessionUnavailable?: boolean;
}

export const ManagementAuthContext = createContext<ManagementAuthContextValue | null>(null);

export function ManagementAuthProvider({ children }: { children: React.ReactNode }) {
  const [loading, setLoading] = useState(true);
  const [authenticated, setAuthenticated] = useState(false);
  const [user, setUser] = useState<ManagementAuthUser | null>(null);
  const [sessionUnavailable, setSessionUnavailable] = useState(false);
  const gate = useRef(new SessionRequestGate());

  async function refreshSession() {
    const requestRevision = gate.current.refresh();
    if (requestRevision == null) return;
    try {
      const response = await getManagementAuthMe();
      if (!gate.current.current(requestRevision)) return;
      setAuthenticated(response.authenticated);
      setUser(response.user);
      setSessionUnavailable(false);
    } catch (error) {
      if (!gate.current.current(requestRevision)) return;
      const rejected = isRejectedSession(error);
      if (rejected) {
        setAuthenticated(false);
        setUser(null);
      }
      // A network failure does not prove logout and must not unmount an established room.
      setSessionUnavailable(!rejected);
    }
  }

  async function logout() {
    const requestRevision = gate.current.logout();
    if (requestRevision == null) return;
    try {
      await logoutManagementAuth();
    } finally {
      gate.current.finishLogout(requestRevision);
      if (!gate.current.current(requestRevision)) return;
      setAuthenticated(false);
      setUser(null);
      setSessionUnavailable(false);
    }
  }

  useEffect(() => {
    const sessionGate = gate.current;
    sessionGate.activate();
    void (async () => {
      try {
        await refreshSession();
      } finally {
        setLoading(false);
      }
    })();
    return () => sessionGate.dispose();
  }, []);

  return (
    <ManagementAuthContext.Provider
      value={{
        loading,
        authenticated,
        user,
        refreshSession,
        logout,
        sessionUnavailable,
      }}
    >
      {children}
    </ManagementAuthContext.Provider>
  );
}
