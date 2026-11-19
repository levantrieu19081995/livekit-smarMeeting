'use client';

import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { ManagementRoomToolbarIcon } from '@/components/management-room/ManagementRoomToolbarIcon';
import styles from '@/styles/management/ManagementRoom.module.css';

interface Props {
  roomRoot: HTMLDivElement | null;
  active: boolean;
  onToggle: () => void;
}

export function ManagementPollBridge({ roomRoot, active, onToggle }: Props) {
  const [mount, setMount] = useState<HTMLElement | null>(null);

  useEffect(() => {
    if (!roomRoot) return;
    function sync() {
      const toolbar = roomRoot?.querySelector('.lk-control-bar');
      if (!toolbar) {
        setMount(null);
        return;
      }
      let target = toolbar.querySelector<HTMLElement>('[data-management-poll-mount]');
      if (!target) {
        target = document.createElement('span');
        target.dataset.managementPollMount = '';
        const chat = toolbar.querySelector('[aria-label*="chat" i], .lk-chat-toggle');
        toolbar.insertBefore(target, chat);
      }
      setMount(target);
    }
    sync();
    const observer = new MutationObserver(sync);
    observer.observe(roomRoot, { childList: true, subtree: true });
    return () => {
      observer.disconnect();
      roomRoot.querySelectorAll('[data-management-poll-mount]').forEach((node) => node.remove());
    };
  }, [roomRoot]);

  useEffect(() => {
    if (!roomRoot || !active) return;
    function clicked(event: MouseEvent) {
      if (
        event.target instanceof Element &&
        event.target.closest('[aria-label*="chat" i], .lk-chat-toggle')
      )
        onToggle();
    }
    roomRoot.addEventListener('click', clicked, true);
    return () => roomRoot.removeEventListener('click', clicked, true);
  }, [roomRoot, active, onToggle]);

  return mount
    ? createPortal(
        <button
          type="button"
          className={`lk-button ${styles.roomToolbarButton}`}
          aria-label="Biểu quyết"
          title="Biểu quyết"
          aria-pressed={active}
          onClick={() => {
            if (!active) {
              const chat = mount.parentElement?.querySelector<HTMLButtonElement>(
                '[aria-label*="chat" i], .lk-chat-toggle',
              );
              if (chat?.getAttribute('aria-pressed') === 'true') chat.click();
            }
            onToggle();
          }}
        >
          <ManagementRoomToolbarIcon name="poll" />
          <span className={styles.roomToolbarLabel}>Biểu quyết</span>
        </button>,
        mount,
      )
    : null;
}
