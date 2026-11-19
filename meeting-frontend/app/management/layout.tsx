import type { ReactNode } from 'react';
import { ManagementAuthGuard } from '@/components/management/ManagementAuthGuard';
import { ManagementAuthProvider } from '@/contexts/ManagementAuthContext';
import { ManagementShell } from '@/components/management/ManagementShell';

export default function ManagementLayout({ children }: { children: ReactNode }) {
  return (
    <ManagementAuthProvider>
      <ManagementAuthGuard>
        <ManagementShell>{children}</ManagementShell>
      </ManagementAuthGuard>
    </ManagementAuthProvider>
  );
}
