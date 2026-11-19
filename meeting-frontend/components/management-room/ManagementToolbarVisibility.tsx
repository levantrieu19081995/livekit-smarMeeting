'use client';

import { useEffect, useId, useLayoutEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import styles from '@/styles/management/ManagementRoom.module.css';

interface ManagementToolbarVisibilityProps {
  roomRoot: HTMLDivElement | null;
}

export function ManagementToolbarVisibility({ roomRoot }: ManagementToolbarVisibilityProps) {
  const toolbarId = useId();
  const [toolbar, setToolbar] = useState<HTMLElement | null>(null);
  const [collapsed, setCollapsed] = useState(false);
  const [toolbarHeight, setToolbarHeight] = useState(0);

  useEffect(() => {
    if (!roomRoot) return;
    const sync = () => setToolbar(roomRoot.querySelector<HTMLElement>('.lk-control-bar'));
    sync();
    const observer = new MutationObserver(sync);
    observer.observe(roomRoot, { childList: true, subtree: true });
    return () => observer.disconnect();
  }, [roomRoot]);

  useLayoutEffect(() => {
    const host = toolbar?.parentElement;
    if (!toolbar || !host) return;
    const originalId = toolbar.getAttribute('id');
    if (!originalId) toolbar.id = toolbarId;
    toolbar.classList.add(styles.toolbarCollapsible);
    toolbar.classList.toggle(styles.toolbarCollapsed, collapsed);
    host.classList.add(styles.toolbarToggleHost);
    host.classList.toggle(styles.toolbarCollapsedHost, collapsed);

    const measure = () => setToolbarHeight(toolbar.getBoundingClientRect().height);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(toolbar);
    observer.observe(host);
    return () => {
      observer.disconnect();
      toolbar.classList.remove(styles.toolbarCollapsible, styles.toolbarCollapsed);
      host.classList.remove(styles.toolbarToggleHost, styles.toolbarCollapsedHost);
      if (!originalId && toolbar.id === toolbarId) toolbar.removeAttribute('id');
    };
  }, [toolbar, toolbarId, collapsed]);

  if (!toolbar?.parentElement) return null;

  return createPortal(
    <button
      type="button"
      className={`${styles.toolbarVisibilityToggle} ${collapsed ? styles.toolbarRestoreButton : ''}`}
      style={{ bottom: collapsed ? undefined : Math.max(8, toolbarHeight - 48) }}
      aria-label={collapsed ? 'Hiện thanh điều khiển' : 'Thu gọn thanh điều khiển'}
      aria-expanded={!collapsed}
      aria-controls={toolbar.id || toolbarId}
      title={collapsed ? 'Hiện thanh điều khiển' : 'Thu gọn thanh điều khiển'}
      onClick={() => setCollapsed((current) => !current)}
    >
      <svg
        width="18"
        height="18"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d={collapsed ? 'm6 15 6-6 6 6' : 'm6 6 12 12M18 6 6 18'} />
      </svg>
    </button>,
    toolbar.parentElement,
  );
}
