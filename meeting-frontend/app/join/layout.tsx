import type { ReactNode } from 'react';
import { ManagementAuthProvider } from '@/contexts/ManagementAuthContext';

export default function JoinLayout({ children }: { children: ReactNode }) {
  return <ManagementAuthProvider>{children}</ManagementAuthProvider>;
}
