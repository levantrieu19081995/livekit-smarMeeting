'use client';

import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import Link from 'next/link';
import { useInstantMeetingCreation } from '@/hooks/useInstantMeetingCreation';
import type { ManagementMeeting } from '@/types/management/meeting';
import { CreatedMeetingDialog } from './CreatedMeetingDialog';
import styles from '@/styles/management/MeetingCreation.module.css';

export function MeetingCreateMenu({
  label = 'Tạo cuộc họp',
  onCreated,
}: {
  label?: string;
  onCreated?: (meeting: ManagementMeeting) => void;
}) {
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const { pending, created, createInstant, closeCreated } = useInstantMeetingCreation(onCreated);
  const [open, setOpen] = useState(false);
  const [position, setPosition] = useState({ left: 0, top: 0, width: 320 });

  useEffect(() => {
    if (!open) return;
    menu.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus({ preventScroll: true });
    function outside(event: PointerEvent) {
      if (
        event.target instanceof Node &&
        !trigger.current?.contains(event.target) &&
        !menu.current?.contains(event.target)
      )
        setOpen(false);
    }
    function key(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false);
        trigger.current?.focus({ preventScroll: true });
      }
    }
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', key);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', key);
    };
  }, [open]);

  function toggle() {
    const bounds = trigger.current?.getBoundingClientRect();
    if (!bounds || pending) return;
    const width = Math.min(340, window.innerWidth - 24);
    setPosition({
      left: Math.max(12, Math.min(bounds.left, window.innerWidth - width - 12)),
      top: Math.max(12, Math.min(bounds.bottom + 6, window.innerHeight - 240)),
      width,
    });
    setOpen((value) => !value);
  }

  function instant() {
    setOpen(false);
    void createInstant();
  }

  return (
    <>
      <button
        ref={trigger}
        type="button"
        className={styles.trigger}
        onClick={toggle}
        disabled={pending}
        aria-haspopup="menu"
        aria-expanded={open}
      >
        <span aria-hidden="true">＋</span>
        {pending ? 'Đang tạo…' : label}
      </button>
      {open
        ? createPortal(
            <div
              ref={menu}
              role="menu"
              aria-label="Chọn kiểu tạo cuộc họp"
              className={styles.menu}
              style={position}
              onKeyDown={(event) => {
                if (!['ArrowDown', 'ArrowUp'].includes(event.key)) return;
                event.preventDefault();
                const items = Array.from(
                  menu.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [],
                );
                const index = items.indexOf(document.activeElement as HTMLElement);
                items[
                  (index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length
                ]?.focus();
              }}
            >
              <button type="button" role="menuitem" onClick={() => void instant()}>
                Bắt đầu cuộc họp tức thì
              </button>
              <Link
                role="menuitem"
                href="/management/meetings/create?mode=LATER"
                onClick={() => setOpen(false)}
              >
                Tạo cuộc họp để sử dụng sau
              </Link>
              <Link
                role="menuitem"
                href="/management/meetings/create?mode=PRIVATE"
                onClick={() => setOpen(false)}
              >
                Tạo cuộc họp riêng tư
              </Link>
              <Link
                role="menuitem"
                href="/management/meetings/create?mode=POINT"
                onClick={() => setOpen(false)}
              >
                Tạo cuộc họp điểm cầu
              </Link>
            </div>,
            document.body,
          )
        : null}
      {created ? <CreatedMeetingDialog meeting={created} onClose={closeCreated} /> : null}
    </>
  );
}
