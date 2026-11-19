'use client';

import { useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import styles from '@/styles/management/ManagementRoom.module.css';

interface ManagementMediaNoticeProps {
  title: string;
  kind: 'microphone' | 'camera' | 'device';
  tone?: 'warning' | 'success';
  collapsedContainer: HTMLDivElement | null;
  children: ReactNode;
}

export function ManagementMediaNotice({
  title,
  kind,
  tone = 'warning',
  collapsedContainer,
  children,
}: ManagementMediaNoticeProps) {
  const [collapsed, setCollapsed] = useState(false);
  const contentId = useId();
  const toggleRef = useRef<HTMLButtonElement>(null);
  const focusAfterToggle = useRef(false);

  useLayoutEffect(() => {
    if (focusAfterToggle.current) {
      toggleRef.current?.focus({ preventScroll: collapsed });
      focusAfterToggle.current = false;
    }
  }, [collapsed, collapsedContainer]);

  const notice = (
    <div
      className={`${styles.mediaStatusItem} ${tone === 'success' ? styles.mediaStatusSuccess : styles.mediaStatusWarning} ${styles.mediaNotice} ${collapsed ? styles.mediaNoticeCollapsed : ''}`}
    >
      <button
        ref={toggleRef}
        type="button"
        className={styles.mediaNoticeToggle}
        aria-label={`${collapsed ? 'Mở' : 'Thu gọn'} thông báo: ${title}`}
        aria-expanded={!collapsed}
        aria-controls={collapsed ? undefined : contentId}
        title={collapsed ? title : 'Thu gọn thông báo'}
        onClick={() => {
          focusAfterToggle.current = true;
          setCollapsed((current) => !current);
        }}
      >
        <svg
          viewBox="0 0 24 24"
          width="16"
          height="16"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          {!collapsed ? (
            <path d="m6 6 12 12M18 6 6 18" />
          ) : kind === 'microphone' ? (
            <>
              <rect x="9" y="2" width="6" height="12" rx="3" />
              <path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8" />
              {tone === 'warning' ? <path d="M3 3l18 18" /> : null}
            </>
          ) : kind === 'camera' ? (
            <>
              <rect x="3" y="6" width="12" height="12" rx="2" />
              <path d="m15 10 6-3v10l-6-3M3 3l18 18" />
            </>
          ) : (
            <>
              <path d="m10.3 3.8-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.7-3.2l-8-14a2 2 0 0 0-3.4 0Z" />
              <path d="M12 9v4M12 17h.01" />
            </>
          )}
        </svg>
      </button>
      {!collapsed ? (
        <div id={contentId}>
          <strong className={styles.mediaNoticeTitle}>{title}</strong>
          {children}
        </div>
      ) : null}
    </div>
  );

  return collapsed && collapsedContainer ? createPortal(notice, collapsedContainer) : notice;
}
