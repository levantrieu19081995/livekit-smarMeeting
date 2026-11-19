'use client';

import {
  ChatCloseIcon,
  ChatToggle,
  useLayoutContext,
  useRoomContext,
} from '@livekit/components-react';
import { RoomEvent } from 'livekit-client';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import {
  getManagementChatHistory,
  saveManagementChatMessage,
} from '@/services/management/managementChatService';
import { ManagementApiError } from '@/services/management/httpClient';
import {
  ManagementRequestCoordinator,
  withManagementDeadline,
} from '@/services/management/managementRequestCoordinator';
import { QnaRefreshScheduler } from '@/services/management/qnaRefreshScheduler';
import { QnaHintGate } from '@/services/management/qnaRoomSync';
import {
  CHAT_CHANGE_TOPIC,
  mergeChatTimeline,
  reusableChatDraft,
  persistChatThenNotify,
  confirmsPendingChat,
} from '@/services/management/chatTimeline';
import type {
  ManagementChatMessageResponse,
  SaveManagementChatMessageRequest,
} from '@/types/management/chat';

export function ManagementRoomChat({
  meetingId,
  participantToken,
  connectionId,
  visible,
}: {
  meetingId: string;
  participantToken: string;
  connectionId: string;
  visible: boolean;
}) {
  const room = useRoomContext();
  const { widget } = useLayoutContext();
  const [messages, setMessages] = useState<ManagementChatMessageResponse[]>([]);
  const [content, setContent] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [denied, setDenied] = useState(false);
  const cursor = useRef(0);
  const lastRead = useRef(0);
  const firstRead = useRef(true);
  const busy = useRef(false);
  const currentScope = `${meetingId}:${connectionId}:${participantToken}`;
  const scope = useRef(currentScope);
  scope.current = currentScope;
  const active = useRef(false);
  const draft = useRef<SaveManagementChatMessageRequest | null>(null);
  const reads = useRef(new ManagementRequestCoordinator());
  const write = useRef<AbortController | null>(null);
  const engine = useRef<QnaRefreshScheduler | null>(null);
  const list = useRef<HTMLUListElement>(null);

  useEffect(() => {
    const requestScope = currentScope;
    active.current = true;
    cursor.current = 0;
    lastRead.current = 0;
    firstRead.current = true;
    draft.current = null;
    busy.current = false;
    setMessages([]);
    setContent('');
    setDenied(false);
    setError(null);
    setActionError(null);
    setSending(false);
    const coordinator = reads.current;
    const refresh = async () => {
      await coordinator.run(
        async (signal) => {
          let afterId = cursor.current;
          let incoming: ManagementChatMessageResponse[] = [];
          let hasMore = false;
          // Bound each read even if a busy room has accumulated a large backlog.
          for (let page = 0; page < 5; page++) {
            const response = await getManagementChatHistory(
              meetingId,
              participantToken,
              afterId,
              signal,
            );
            incoming = mergeChatTimeline(incoming, response.content);
            hasMore = response.hasMore;
            const next = response.nextAfterId;
            if (next != null && next > afterId) afterId = next;
            else if (hasMore)
              throw new Error('Không thể xác nhận vị trí lịch sử chat. Hãy tải lại.');
            if (!hasMore) break;
          }
          return { incoming, afterId, hasMore };
        },
        ({ incoming, afterId, hasMore }) => {
          if (!active.current || scope.current !== requestScope) return;
          cursor.current = Math.max(cursor.current, afterId);
          if (firstRead.current) {
            lastRead.current = afterId;
            firstRead.current = false;
          }
          setMessages((previous) => mergeChatTimeline(previous, incoming));
          const pending = draft.current;
          if (pending && confirmsPendingChat(incoming, pending, connectionId)) {
            draft.current = null;
            setContent((value) => (value.trim() === pending.content ? '' : value));
            setActionError(null);
          }
          setError(null);
          setDenied(false);
          if (hasMore) engine.current?.request();
        },
        (failure) => {
          if (!active.current || scope.current !== requestScope) return;
          if (failure instanceof ManagementApiError && [401, 403, 404].includes(failure.status)) {
            setMessages([]);
            setDenied(true);
          }
          setError(failure instanceof Error ? failure.message : 'Không thể tải lịch sử chat.');
        },
      );
    };
    const scheduler = new QnaRefreshScheduler(refresh, () => document.visibilityState !== 'hidden');
    engine.current = scheduler;
    const hints = new QnaHintGate(() => scheduler.request(), 1000);
    const wake = () => scheduler.request();
    const hint = (payload: Uint8Array, _participant: unknown, _kind: unknown, topic?: string) => {
      if (topic !== CHAT_CHANGE_TOPIC || payload.byteLength > 128) return;
      try {
        const value: unknown = JSON.parse(
          new TextDecoder('utf-8', { fatal: true }).decode(payload),
        );
        if (
          value &&
          typeof value === 'object' &&
          Object.keys(value).length === 1 &&
          'meetingId' in value &&
          value.meetingId === meetingId
        )
          hints.request();
      } catch {
        /* Untrusted packets cannot add or modify chat messages. */
      }
    };
    const poll = window.setInterval(() => scheduler.poll(), 5000);
    room.on(RoomEvent.DataReceived, hint);
    room.on(RoomEvent.Reconnected, wake);
    window.addEventListener('online', wake);
    document.addEventListener('visibilitychange', wake);
    scheduler.request();
    return () => {
      active.current = false;
      scheduler.dispose();
      hints.dispose();
      coordinator.invalidate();
      write.current?.abort();
      engine.current = null;
      window.clearInterval(poll);
      room.off(RoomEvent.DataReceived, hint);
      room.off(RoomEvent.Reconnected, wake);
      window.removeEventListener('online', wake);
      document.removeEventListener('visibilitychange', wake);
    };
  }, [currentScope, meetingId, participantToken, room]);

  useEffect(() => {
    if (visible && messages.length) lastRead.current = messages[messages.length - 1].id;
    const unread = messages.filter(
      (message) => message.id > lastRead.current && message.senderConnectionId !== connectionId,
    ).length;
    if (widget.state?.unreadMessages !== unread)
      widget.dispatch?.({ msg: 'unread_msg', count: unread });
    if (visible) list.current?.scrollTo?.({ top: list.current.scrollHeight });
  }, [messages, visible, widget, connectionId]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current || denied || !content.trim()) return;
    const requestScope = currentScope;
    busy.current = true;
    setSending(true);
    setActionError(null);
    draft.current = reusableChatDraft(draft.current, content, () => crypto.randomUUID());
    const pending = draft.current;
    const controller = new AbortController();
    write.current = controller;
    try {
      const saved = await persistChatThenNotify(
        () =>
          withManagementDeadline(
            (signal) => saveManagementChatMessage(meetingId, participantToken, pending, signal),
            controller,
          ),
        (receipt) =>
          Promise.allSettled([
            room.localParticipant.publishData(
              new TextEncoder().encode(JSON.stringify({ meetingId })),
              { topic: CHAT_CHANGE_TOPIC, reliable: true },
            ),
            // Existing deployed prefab clients receive confirmed messages during rollout.
            room.localParticipant.publishData(
              new TextEncoder().encode(
                JSON.stringify({
                  id: receipt.clientMessageId,
                  timestamp: new Date(receipt.sentAt).getTime(),
                  message: receipt.content,
                }),
              ),
              { topic: 'lk-chat-topic', reliable: true },
            ),
          ]),
      );
      if (!active.current || scope.current !== requestScope) return;
      // Do not advance the history cursor here: earlier messages may still be in flight.
      setMessages((previous) => mergeChatTimeline(previous, [saved]));
      setContent('');
      draft.current = null;
      engine.current?.request();
      // The DB receipt already confirms delivery; a lost hint is recovered by polling.
    } catch (failure) {
      if (!active.current || scope.current !== requestScope) return;
      if (draft.current?.clientMessageId !== pending.clientMessageId) return;
      if (failure instanceof ManagementApiError && [401, 403, 404].includes(failure.status))
        setDenied(true);
      setActionError(
        failure instanceof Error
          ? failure.message
          : 'Chưa xác nhận được tin nhắn. Thử gửi lại để kiểm tra.',
      );
    } finally {
      if (active.current && scope.current === requestScope) {
        busy.current = false;
        setSending(false);
        write.current = null;
      }
    }
  }

  return (
    <div className="lk-chat" style={{ display: visible ? 'grid' : 'none' }}>
      <div className="lk-chat-header">
        Messages
        <ChatToggle className="lk-close-button">
          <ChatCloseIcon />
        </ChatToggle>
      </div>
      <ul className="lk-list lk-chat-messages" ref={list}>
        {messages.map((message, index) => {
          const previous = messages[index - 1];
          const sameSender =
            previous &&
            previous.senderConnectionId &&
            previous.senderConnectionId === message.senderConnectionId;
          const time = new Date(message.sentAt);
          const showTime =
            !sameSender || time.getTime() - new Date(previous.sentAt).getTime() >= 60_000;
          return (
            <li
              key={message.id}
              className="lk-chat-entry"
              data-lk-message-origin={
                message.senderConnectionId === connectionId ? 'local' : 'remote'
              }
              title={time.toLocaleTimeString()}
            >
              {(!sameSender || showTime) && (
                <span className="lk-meta-data">
                  {!sameSender && (
                    <strong className="lk-participant-name">{message.senderDisplayName}</strong>
                  )}
                  {showTime && (
                    <span className="lk-timestamp">
                      {time.toLocaleTimeString([], { timeStyle: 'short' })}
                    </span>
                  )}
                </span>
              )}
              <span className="lk-message-body">{message.content}</span>
            </li>
          );
        })}
        {(actionError || error) && <li role="alert">{actionError || error}</li>}
      </ul>
      <form className="lk-chat-form" onSubmit={submit}>
        <input
          className="lk-form-control lk-chat-form-input"
          type="text"
          maxLength={10000}
          placeholder="Enter a message..."
          value={content}
          disabled={sending || denied}
          onChange={(event) => setContent(event.target.value)}
          onKeyDown={(event) => event.stopPropagation()}
          onKeyUp={(event) => event.stopPropagation()}
        />
        <button
          type="submit"
          className="lk-button lk-chat-form-button"
          disabled={sending || denied || !content.trim()}
        >
          Send
        </button>
      </form>
    </div>
  );
}
