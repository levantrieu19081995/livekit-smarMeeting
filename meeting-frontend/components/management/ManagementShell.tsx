'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { type PropsWithChildren, useMemo } from 'react';
import {
  ManagementTopNav,
  type ManagementNavIcon,
  type ManagementTopNavItem,
} from '@/components/management/ManagementTopNav';
import { useManagementAuth } from '@/hooks/useManagementAuth';
import styles from '@/styles/management/ManagementShell.module.css';

const BASE_NAV_ITEMS: Array<{
  label: string;
  href: string;
  enabled: boolean;
  icon: ManagementNavIcon;
}> = [
  { label: 'Tổng quan', href: '/management', enabled: true, icon: 'overview' },
  { label: 'Cuộc họp', href: '/management/meetings', enabled: true, icon: 'meetings' },
  { label: 'Biên bản', href: '/management/minutes', enabled: true, icon: 'minutes' },
  { label: 'Người dùng', href: '/management/users', enabled: true, icon: 'participants' },
  { label: 'Cài đặt', href: '/management/settings', enabled: true, icon: 'settings' },
];

const BREADCRUMB_LABELS: Record<string, string> = {
  management: 'Tổng quan',
  meetings: 'Cuộc họp',
  qa: 'Hỏi đáp',
  minutes: 'Biên bản cuộc họp',
  create: 'Tạo cuộc họp',
  users: 'Người dùng',
  settings: 'Cài đặt',
};

function formatSegment(segment: string) {
  return BREADCRUMB_LABELS[segment] ?? decodeURIComponent(segment);
}

export function ManagementShell({ children }: PropsWithChildren) {
  const pathname = usePathname();
  const { authenticated, user, logout } = useManagementAuth();
  const isDashboard = pathname === '/management';
  const isMeetingsList = pathname === '/management/meetings';
  const isMinutesList = pathname === '/management/minutes';
  const isUsersList = pathname === '/management/users';
  const isMinutesDetailRoute = /^\/management\/minutes\/[^/]+$/.test(pathname);
  const isMeetingDetailRoute =
    pathname !== '/management/meetings/create' && /^\/management\/meetings\/[^/]+$/.test(pathname);
  const usesStandaloneHero =
    isDashboard ||
    isMeetingsList ||
    isMinutesList ||
    isUsersList ||
    isMeetingDetailRoute ||
    isMinutesDetailRoute;

  const breadcrumbs = useMemo(() => {
    const segments = pathname.split('/').filter(Boolean);
    const items = [{ key: 'brand', label: 'CUSC Smart Meeting', href: '/' }];
    let currentPath = '';

    for (const [index, segment] of segments.entries()) {
      currentPath += `/${segment}`;
      items.push({
        key:
          currentPath === '/management'
            ? 'management-root'
            : `management-${segments.slice(1, index + 1).join('-')}`,
        label: formatSegment(segment),
        href: currentPath,
      });
    }

    return items;
  }, [pathname]);

  function isNavItemActive(href: string) {
    if (href === '/management') return pathname === href;
    return pathname === href || pathname.startsWith(`${href}/`);
  }

  const currentTitle = breadcrumbs[breadcrumbs.length - 1]?.label ?? 'Tổng quan';
  const navItems: ManagementTopNavItem[] = BASE_NAV_ITEMS.filter((item) => item.enabled).map(
    (item) => ({
      label: item.label,
      href: item.href,
      icon: item.icon,
      active: isNavItemActive(item.href),
    }),
  );

  return (
    <div className={styles.shell}>
      <ManagementTopNav
        authenticated={authenticated}
        user={user}
        navItems={navItems}
        onLogout={logout}
      />

      <div className={styles.mainColumn}>
        {!usesStandaloneHero ? (
          <header className={styles.header}>
            <div className={styles.headerMain}>
              <div className={styles.breadcrumbs} aria-label="Breadcrumb">
                {breadcrumbs.map((item, index) => (
                  <span key={item.key} className={styles.breadcrumbItem}>
                    {index > 0 ? <span className={styles.breadcrumbDivider}>/</span> : null}
                    {index === breadcrumbs.length - 1 ? (
                      item.label
                    ) : (
                      <Link href={item.href}>{item.label}</Link>
                    )}
                  </span>
                ))}
              </div>
              <h2 className={styles.headerTitle}>{currentTitle}</h2>
            </div>
          </header>
        ) : null}

        <main
          className={`${styles.content} ${isDashboard ? styles.dashboardContent : ''} ${
            isMeetingsList ? styles.meetingsListContent : ''
          } ${isUsersList ? styles.usersListContent : ''} ${
            isMeetingDetailRoute ? styles.meetingDetailContent : ''
          }`}
        >
          {children}
        </main>
      </div>
    </div>
  );
}
