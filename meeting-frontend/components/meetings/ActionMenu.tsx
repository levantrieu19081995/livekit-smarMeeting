'use client';

import { useEffect, useRef, useState } from 'react';
import styles from '@/styles/management/MeetingPages.module.css';

type ActionIcon = 'view' | 'join' | 'copy' | 'edit' | 'delete';

interface ActionItem {
  label: string;
  icon: ActionIcon;
  onSelect: () => void;
  danger?: boolean;
}

interface ActionMenuProps {
  label?: string;
  items: ActionItem[];
}

function MenuIcon({ name }: { name: ActionIcon }) {
  switch (name) {
    case 'view':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 5c5.23 0 9.27 4.11 10.52 6.12a1.5 1.5 0 0 1 0 1.76C21.27 14.89 17.23 19 12 19S2.73 14.89 1.48 12.88a1.5 1.5 0 0 1 0-1.76C2.73 9.11 6.77 5 12 5Zm0 2C8.18 7 4.96 9.88 3.53 12 4.96 14.12 8.18 17 12 17s7.04-2.88 8.47-5C19.04 9.88 15.82 7 12 7Zm0 2.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5Z" />
        </svg>
      );
    case 'join':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M3 5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v4h-2V5H5v10h6v2H5a2 2 0 0 1-2-2V5Zm10.29 13.29 3.3-3.29H9v-2h7.59l-3.3-3.29L14.7 8.3 20.4 14l-5.7 5.7-1.41-1.41Z" />
        </svg>
      );
    case 'copy':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M8 7a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2V7Zm2 0v10h9V7h-9ZM5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1h-2V4H4v9h1v2Z" />
        </svg>
      );
    case 'edit':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="m4 16.25 9.19-9.19 3.75 3.75L7.75 20H4v-3.75Zm10.6-10.6 1.2-1.19a2 2 0 0 1 2.82 0l.73.73a2 2 0 0 1 0 2.82l-1.19 1.2-3.75-3.75Z" />
        </svg>
      );
    case 'delete':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M9 3h6l1 2h5v2H3V5h5l1-2Zm1 6h2v8h-2V9Zm4 0h2v8h-2V9ZM6 7h12l-1 13a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2L6 7Z" />
        </svg>
      );
  }
}

function DotsIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M6 10.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3Zm6 0a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3Zm6 0a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3Z" />
    </svg>
  );
}

export function ActionMenu({ label = 'Tác vụ', items }: ActionMenuProps) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    function handlePointerDown(event: MouseEvent) {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    }

    function handleEscape(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false);
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
    <div className={styles.actionMenu} ref={containerRef}>
      <button
        type="button"
        className={styles.menuTrigger}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={label}
        onClick={() => setOpen((current) => !current)}
      >
        <DotsIcon />
      </button>

      {open ? (
        <div className={styles.menuList} role="menu">
          {items.map((item) => (
            <button
              key={item.label}
              type="button"
              className={`${styles.menuItem} ${item.danger ? styles.menuItemDanger : ''}`}
              role="menuitem"
              onClick={() => {
                setOpen(false);
                item.onSelect();
              }}
            >
              <span className={styles.menuItemIcon}>
                <MenuIcon name={item.icon} />
              </span>
              <span>{item.label}</span>
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
