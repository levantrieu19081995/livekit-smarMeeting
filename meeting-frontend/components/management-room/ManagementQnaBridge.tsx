'use client';

import { useEffect, useState, useRef } from 'react';
import { createPortal } from 'react-dom';
import { ManagementRoomToolbarIcon } from '@/components/management-room/ManagementRoomToolbarIcon';
import { useRoomContext } from '@livekit/components-react';
import { RoomEvent, type RemoteParticipant, type DataPacket_Kind } from 'livekit-client';
import { isQnaHint, publishQnaHint, QnaHintGate } from '@/services/management/qnaRoomSync';
import styles from '@/styles/management/ManagementRoom.module.css';

interface Props {
  roomRoot: HTMLDivElement | null;
  active: boolean;
  panelId: string;
  onToggle: () => void;
  meetingId: string;
  changeRevision: number;
  onRefresh: () => void;
}

const CHAT_BUTTON = '[aria-label*="chat" i], .lk-chat-toggle';

export function ManagementQnaBridge({
  roomRoot,
  active,
  panelId,
  onToggle,
  meetingId,
  changeRevision,
  onRefresh,
}: Props) {
  const [mount, setMount] = useState<HTMLElement | null>(null);
  const room = useRoomContext();
  const published = useRef({ meetingId, revision: changeRevision });

  useEffect(() => {
    if (published.current.meetingId !== meetingId) {
      published.current = { meetingId, revision: changeRevision };
      return;
    }
    if (published.current.revision === changeRevision) return;
    published.current.revision = changeRevision;
    void publishQnaHint(meetingId, (payload, options) =>
      room.localParticipant.publishData(payload, options),
    );
  }, [room, meetingId, changeRevision]);

  useEffect(() => {
    if (!active) return;
    const gate = new QnaHintGate(() => {
      if (document.visibilityState === 'visible') onRefresh();
    });
    function received(
      payload: Uint8Array,
      _participant?: RemoteParticipant,
      _kind?: DataPacket_Kind,
      topic?: string,
    ) {
      if (document.visibilityState === 'visible' && isQnaHint(payload, topic, meetingId))
        gate.request();
    }
    function reconnected() {
      if (document.visibilityState === 'visible') gate.request();
    }
    room.on(RoomEvent.DataReceived, received);
    room.on(RoomEvent.Connected, reconnected);
    room.on(RoomEvent.Reconnected, reconnected);
    return () => {
      gate.dispose();
      room.off(RoomEvent.DataReceived, received);
      room.off(RoomEvent.Connected, reconnected);
      room.off(RoomEvent.Reconnected, reconnected);
    };
  }, [room, active, meetingId, onRefresh]);

  useEffect(() => {
    if (!roomRoot) return;
    const root = roomRoot;
    function sync() {
      const toolbar = root.querySelector('.lk-control-bar');
      if (!toolbar) {
        setMount(null);
        return;
      }
      let target = toolbar.querySelector<HTMLElement>('[data-management-qna-mount]');
      if (!target) {
        target = document.createElement('span');
        target.dataset.managementQnaMount = '';
        toolbar.insertBefore(target, toolbar.querySelector(CHAT_BUTTON));
      }
      setMount(target);
    }
    sync();
    const observer = new MutationObserver(sync);
    observer.observe(root, { childList: true, subtree: true });
    return () => {
      observer.disconnect();
      root.querySelectorAll('[data-management-qna-mount]').forEach((node) => node.remove());
    };
  }, [roomRoot]);

  useEffect(() => {
    if (!roomRoot || !active) return;
    function clicked(event: MouseEvent) {
      if (event.target instanceof Element && event.target.closest(CHAT_BUTTON)) onToggle();
    }
    roomRoot.addEventListener('click', clicked, true);
    return () => roomRoot.removeEventListener('click', clicked, true);
  }, [roomRoot, active, onToggle]);

  return mount
    ? createPortal(
        <button
          type="button"
          className={`lk-button ${styles.roomToolbarButton}`}
          aria-label="Hỏi đáp"
          title="Hỏi đáp"
          aria-pressed={active}
          aria-expanded={active}
          aria-controls={panelId}
          onClick={() => {
            if (!active) {
              const chat = mount.parentElement?.querySelector<HTMLButtonElement>(CHAT_BUTTON);
              if (chat?.getAttribute('aria-pressed') === 'true') chat.click();
            }
            onToggle();
          }}
        >
          <ManagementRoomToolbarIcon name="qna" />
          <span className={styles.roomToolbarLabel}>Hỏi đáp</span>
        </button>,
        mount,
      )
    : null;
}
