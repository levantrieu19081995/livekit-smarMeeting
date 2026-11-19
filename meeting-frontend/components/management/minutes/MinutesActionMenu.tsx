'use client';

import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { MeetingMinutesListItem, MinutesTableAction } from '@/types/management/minutes';
import styles from '@/styles/management/Minutes.module.css';

interface MinutesActionMenuProps {
  meeting: MeetingMinutesListItem;
  open: boolean;
  onOpen: () => void;
  onClose: () => void;
  onAction: (action: MinutesTableAction) => void;
}

function primaryAction(meeting: MeetingMinutesListItem) {
  if (meeting.status === 'NOT_GENERATED') return { icon: '✨', label: 'Tạo biên bản' };
  if (meeting.status === 'PROCESSING') return { icon: '⏳', label: 'Xem tiến độ' };
  if (meeting.status === 'FAILED') return { icon: '↻', label: 'Thử lại' };
  return { icon: '📝', label: 'Xem biên bản' };
}

export function MinutesActionMenu({
  meeting,
  open,
  onOpen,
  onClose,
  onAction,
}: MinutesActionMenuProps) {
  const buttonRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<{ top: number; left: number } | null>(null);
  const menuId = `minutes-actions-${meeting.meetingId}`;
  const primary = primaryAction(meeting);

  useLayoutEffect(() => {
    if (!open || !buttonRef.current) {
      setPosition(null);
      return;
    }
    const rect = buttonRef.current.getBoundingClientRect();
    const menuWidth = 208;
    const menuHeight = 180;
    const gap = 6;
    const left = Math.max(8, Math.min(rect.right - menuWidth, window.innerWidth - menuWidth - 8));
    const top =
      rect.bottom + menuHeight + gap <= window.innerHeight
        ? rect.bottom + gap
        : Math.max(8, rect.top - menuHeight - gap);
    setPosition({ top, left });
  }, [open]);

  useEffect(() => {
    if (!open) return;

    function handlePointerDown(event: PointerEvent) {
      const target = event.target as Node;
      if (buttonRef.current?.contains(target) || menuRef.current?.contains(target)) return;
      onClose();
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose();
        buttonRef.current?.focus();
      }
    }

    document.addEventListener('pointerdown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    window.addEventListener('resize', onClose);
    window.addEventListener('scroll', onClose, true);
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('resize', onClose);
      window.removeEventListener('scroll', onClose, true);
    };
  }, [onClose, open]);

  function runAction(action: MinutesTableAction) {
    onClose();
    onAction(action);
  }

  const menu =
    open && position ? (
      <div
        ref={menuRef}
        id={menuId}
        role="menu"
        aria-label={`Thao tác cho ${meeting.title}`}
        className={styles.minutesActionMenu}
        style={position}
      >
        <button type="button" role="menuitem" onClick={() => runAction('PRIMARY')}>
          <span aria-hidden="true">{primary.icon}</span>
          {primary.label}
        </button>
        <div className={styles.actionMenuDivider} />
        <button type="button" role="menuitem" onClick={() => runAction('DOCUMENTS')}>
          <span aria-hidden="true">📄</span>
          Xem tài liệu
        </button>
        <button type="button" role="menuitem" onClick={() => runAction('DETAILS')}>
          <span aria-hidden="true">ⓘ</span>
          Thông tin cuộc họp
        </button>
        <button type="button" role="menuitem" onClick={() => runAction('OPEN_MEETING')}>
          <span aria-hidden="true">↗</span>
          Mở cuộc họp
        </button>
      </div>
    ) : null;

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        className={styles.moreButton}
        aria-label="Mở menu thao tác"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={open ? onClose : onOpen}
        onMouseEnter={onOpen}
      >
        ⋮
      </button>
      {typeof document !== 'undefined' ? createPortal(menu, document.body) : null}
    </>
  );
}
