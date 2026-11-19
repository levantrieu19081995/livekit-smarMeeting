'use client';

import { HomeBrandLink } from '@/components/home/HomeBrandLink';

import Link from 'next/link';
import { type ReactNode, useEffect, useRef, useState } from 'react';
import type { ManagementAuthUser } from '@/types/management/auth';
import styles from '@/styles/management/ManagementShell.module.css';

export type ManagementNavIcon = 'overview' | 'meetings' | 'minutes' | 'participants' | 'settings';

export interface ManagementTopNavItem {
  label: string;
  href: string;
  icon: ManagementNavIcon;
  active: boolean;
}

interface ManagementTopNavProps {
  authenticated: boolean;
  user: ManagementAuthUser | null;
  navItems: ManagementTopNavItem[];
  onLogout: () => void | Promise<void>;
}

function StrokeIcon({ children }: { children: ReactNode }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  );
}

function Icon({ name }: { name: ManagementNavIcon | 'search' | 'bell' | 'menu' | 'logout' }) {
  switch (name) {
    case 'overview':
      return (
        <StrokeIcon>
          <rect x="3.5" y="3.5" width="6.5" height="6.5" rx="1" />
          <rect x="14" y="3.5" width="6.5" height="6.5" rx="1" />
          <rect x="3.5" y="14" width="6.5" height="6.5" rx="1" />
          <rect x="14" y="14" width="6.5" height="6.5" rx="1" />
        </StrokeIcon>
      );
    case 'meetings':
      return (
        <StrokeIcon>
          <path d="M7 3v3" />
          <path d="M17 3v3" />
          <rect x="3.5" y="5" width="17" height="16" rx="2.5" />
          <path d="M3.5 9.5h17" />
        </StrokeIcon>
      );
    case 'minutes':
      return (
        <StrokeIcon>
          <path d="M6 3.5h8l4 4v13H6z" />
          <path d="M14 3.5v4h4" />
          <path d="M9 12h6" />
          <path d="M9 16h6" />
        </StrokeIcon>
      );
    case 'participants':
      return (
        <StrokeIcon>
          <circle cx="9" cy="8" r="3" />
          <path d="M3.5 19c.6-3.2 2.5-5 5.5-5s4.9 1.8 5.5 5" />
          <path d="M15.5 6.2a2.7 2.7 0 0 1 0 5.2" />
          <path d="M17 14c2 .5 3.2 2.1 3.5 4.5" />
        </StrokeIcon>
      );
    case 'settings':
      return (
        <StrokeIcon>
          <path d="M4 7h10" />
          <path d="M18 7h2" />
          <circle cx="16" cy="7" r="2" />
          <path d="M4 17h2" />
          <path d="M10 17h10" />
          <circle cx="8" cy="17" r="2" />
        </StrokeIcon>
      );
    case 'search':
      return (
        <StrokeIcon>
          <circle cx="11" cy="11" r="6.5" />
          <path d="m16 16 4 4" />
        </StrokeIcon>
      );
    case 'bell':
      return (
        <StrokeIcon>
          <path d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" />
          <path d="M10 21h4" />
        </StrokeIcon>
      );
    case 'menu':
      return (
        <StrokeIcon>
          <path d="M4 7h16" />
          <path d="M4 12h16" />
          <path d="M4 17h16" />
        </StrokeIcon>
      );
    case 'logout':
      return (
        <StrokeIcon>
          <path d="M10 4H5v16h5" />
          <path d="M14 8l4 4-4 4" />
          <path d="M18 12H9" />
        </StrokeIcon>
      );
  }
}

function getInitials(user: ManagementAuthUser | null) {
  return user?.fullName?.slice(0, 2).toUpperCase() || 'QT';
}

function Brand() {
  return (
    <HomeBrandLink className={styles.topBrand}>
      <span className={styles.topBrandCusc}>CUSC</span>
      <span className={styles.topBrandName}>Smart Meeting</span>
    </HomeBrandLink>
  );
}

function NavLinks({
  navItems,
  onNavigate,
}: {
  navItems: ManagementTopNavItem[];
  onNavigate?: () => void;
}) {
  return (
    <nav className={styles.topNavMenu} aria-label="Điều hướng quản lý">
      {navItems.map((item) => (
        <Link
          key={item.href}
          href={item.href}
          className={`${styles.topNavItem} ${item.active ? styles.topNavItemActive : ''}`}
          aria-current={item.active ? 'page' : undefined}
          onClick={onNavigate}
        >
          <span className={styles.topNavIcon}>
            <Icon name={item.icon} />
          </span>
          <span>{item.label}</span>
        </Link>
      ))}
    </nav>
  );
}

export function ManagementTopNav({
  authenticated,
  user,
  navItems,
  onLogout,
}: ManagementTopNavProps) {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [accountOpen, setAccountOpen] = useState(false);
  const accountRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    function handlePointerDown(event: MouseEvent) {
      if (!accountRef.current?.contains(event.target as Node)) {
        setAccountOpen(false);
      }
    }

    function handleEscape(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setAccountOpen(false);
        setDrawerOpen(false);
      }
    }

    window.addEventListener('mousedown', handlePointerDown);
    window.addEventListener('keydown', handleEscape);
    return () => {
      window.removeEventListener('mousedown', handlePointerDown);
      window.removeEventListener('keydown', handleEscape);
    };
  }, []);

  return (
    <header className={styles.topNav}>
      <div className={styles.topNavInner}>
        <button
          type="button"
          className={styles.mobileMenuButton}
          aria-label="Mở menu quản lý"
          aria-expanded={drawerOpen}
          onClick={() => setDrawerOpen(true)}
        >
          <Icon name="menu" />
        </button>

        <Brand />

        <div className={styles.desktopNav}>
          <NavLinks navItems={navItems} />
        </div>

        <div className={styles.topNavRight}>
          <div className={styles.searchVisual} aria-label="Tìm kiếm">
            <Icon name="search" />
            <span>Tìm kiếm...</span>
          </div>

          <span className={styles.bellVisual} aria-label="Thông báo" title="Thông báo">
            <Icon name="bell" />
          </span>

          <div className={styles.accountMenu} ref={accountRef}>
            <button
              type="button"
              className={styles.accountButton}
              aria-haspopup="menu"
              aria-expanded={accountOpen}
              onClick={() => setAccountOpen((current) => !current)}
            >
              <span className={styles.userAvatar}>{getInitials(user)}</span>
              <span className={styles.accountText}>
                <strong>{user?.fullName || 'Khách'}</strong>
                <span>{user?.systemRole || 'Chưa đăng nhập'}</span>
              </span>
            </button>

            {accountOpen ? (
              <div className={styles.accountDropdown} role="menu">
                <div className={styles.accountDetails}>
                  <strong>{user?.fullName || 'Khách'}</strong>
                  <span>{user?.email || 'Chưa đăng nhập'}</span>
                  <small>{user?.systemRole || 'UNKNOWN'}</small>
                </div>
                {authenticated ? (
                  <button
                    type="button"
                    className={styles.accountLogout}
                    role="menuitem"
                    onClick={() => {
                      setAccountOpen(false);
                      void onLogout();
                    }}
                  >
                    <Icon name="logout" />
                    <span>Đăng xuất</span>
                  </button>
                ) : null}
              </div>
            ) : null}
          </div>
        </div>
      </div>

      {drawerOpen ? (
        <>
          <button
            type="button"
            className={styles.drawerOverlay}
            aria-label="Đóng menu quản lý"
            onClick={() => setDrawerOpen(false)}
          />
          <aside className={styles.mobileDrawer}>
            <Brand />
            <NavLinks navItems={navItems} onNavigate={() => setDrawerOpen(false)} />
          </aside>
        </>
      ) : null}
    </header>
  );
}
