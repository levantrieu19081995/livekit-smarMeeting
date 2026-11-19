'use client';

import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useRoomContext } from '@livekit/components-react';
import { RoomEvent, type RemoteParticipant, type DataPacket_Kind } from 'livekit-client';
import { ManagementRoomToolbarIcon } from './ManagementRoomToolbarIcon';
import { isSpeakingHint, publishSpeakingHint } from '@/services/management/speakingRoomSync';
import { QnaHintGate } from '@/services/management/qnaRoomSync';
import styles from '@/styles/management/ManagementRoom.module.css';

interface Props {
  roomRoot: HTMLDivElement | null;
  active: boolean;
  panelId: string;
  meetingId: string;
  changeRevision: number;
  onToggle: () => void;
  onRefresh: () => void;
}

const CHAT_BUTTON = '[aria-label*="chat" i], .lk-chat-toggle';

export function ManagementSpeakingBridge({
  roomRoot,
  active,
  panelId,
  meetingId,
  changeRevision,
  onToggle,
  onRefresh,
}: Props) {
  const room = useRoomContext();
  const [mount, setMount] = useState<HTMLElement | null>(null);
  const published = useRef({ meetingId, revision: changeRevision });

  useEffect(() => {
    if (published.current.meetingId !== meetingId) {
      published.current = { meetingId, revision: changeRevision };
      return;
    }
    if (published.current.revision === changeRevision) return;
    published.current.revision = changeRevision;
    void publishSpeakingHint(meetingId, (payload, options) =>
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
      if (document.visibilityState === 'visible' && isSpeakingHint(payload, topic, meetingId))
        gate.request();
    }
    function reconnected() {
      gate.request();
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
      let target = toolbar.querySelector<HTMLElement>('[data-management-speaking-mount]');
      if (!target) {
        target = document.createElement('span');
        target.dataset.managementSpeakingMount = '';
        toolbar.insertBefore(target, toolbar.querySelector(CHAT_BUTTON));
      }
      setMount(target);
    }
    sync();
    const observer = new MutationObserver(sync);
    observer.observe(root, { childList: true, subtree: true });
    return () => {
      observer.disconnect();
      root.querySelectorAll('[data-management-speaking-mount]').forEach((node) => node.remove());
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
          aria-label="Lịch sử phát biểu"
          title="Lịch sử phát biểu"
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
          <ManagementRoomToolbarIcon name="speaking" />
          <span className={styles.roomToolbarLabel}>Lịch sử phát biểu</span>
        </button>,
        mount,
      )
    : null;
}
