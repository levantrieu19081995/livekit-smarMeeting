import type { AnchorHTMLAttributes, ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ManagementAuthContext } from '@/contexts/ManagementAuthContext';
import type { ManagementAuthUser } from '@/types/management/auth';
import { ManagementShell } from './ManagementShell';
import ManagementPage from '@/app/management/page';

vi.mock('next/navigation', () => ({ usePathname: () => '/management/minutes' }));
vi.mock('next/link', () => ({
  default: ({ children, ...props }: AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a {...props}>{children}</a>
  ),
}));
vi.mock('@/components/meetings/ManagementDashboardPage', () => ({
  ManagementDashboardPage: () => <p>Dashboard available</p>,
}));

function session(role: ManagementAuthUser['systemRole'], children: ReactNode) {
  return renderToStaticMarkup(
    <ManagementAuthContext.Provider
      value={{
        loading: false,
        authenticated: true,
        user: {
          publicId: 'navigation-user',
          username: 'navigation-user',
          fullName: 'Navigation User',
          email: 'navigation@example.test',
          avatarUrl: null,
          provider: 'LOCAL',
          systemRole: role,
        },
        logout: async () => {},
        refreshSession: async () => {},
      }}
    >
      {children}
    </ManagementAuthContext.Provider>,
  );
}

describe('Open management navigation', () => {
  for (const role of ['USER', 'MEETING_MANAGER', 'ADMIN'] as const) {
    it(`shows every menu destination for ${role}`, () => {
      const markup = session(role, <ManagementShell>Page content</ManagementShell>);
      for (const path of [
        '/management',
        '/management/meetings',
        '/management/minutes',
        '/management/users',
        '/management/settings',
      ]) {
        expect(markup).toContain(`href="${path}"`);
      }
      expect(markup).toContain('Page content');
    });

    it(`renders the overview for ${role} without redirecting to minutes`, () => {
      expect(session(role, <ManagementPage />)).toContain('Dashboard available');
    });
  }
});
