import { createContext, useCallback, useContext, useEffect, useMemo, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { auth } from '../api/endpoints';
import { authEvents, SIGNED_OUT } from '../api/client';
import type { User } from '../api/types';

type Status = 'loading' | 'in' | 'out';

interface AuthValue {
  user: User | null;
  status: Status;
  /** Call after a successful sign-in / register so the app switches state without a round trip. */
  setUser: (user: User) => void;
  signOut: () => Promise<void>;
}

const Ctx = createContext<AuthValue | null>(null);
const ME = ['me'] as const;

export function AuthProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();
  const me = useQuery({ queryKey: ME, queryFn: auth.me, staleTime: Infinity, retry: false });

  const forget = useCallback(() => {
    qc.setQueryData(ME, null);
    // Nothing from the previous user may linger in the cache.
    qc.removeQueries({ predicate: (q) => q.queryKey[0] !== ME[0] });
  }, [qc]);

  useEffect(() => {
    authEvents.addEventListener(SIGNED_OUT, forget);
    return () => authEvents.removeEventListener(SIGNED_OUT, forget);
  }, [forget]);

  const value = useMemo<AuthValue>(
    () => ({
      user: me.data ?? null,
      status: me.isPending ? 'loading' : me.data ? 'in' : 'out',
      setUser: (user) => {
        qc.removeQueries({ predicate: (q) => q.queryKey[0] !== ME[0] });
        qc.setQueryData(ME, user);
      },
      signOut: async () => {
        try {
          await auth.logout();
        } finally {
          forget();
        }
      },
    }),
    [me.data, me.isPending, qc, forget],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthValue {
  const value = useContext(Ctx);
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>');
  return value;
}
