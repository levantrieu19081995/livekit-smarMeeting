'use client';

import { useEffect, useId, useRef, useState } from 'react';
import type { QnaContext, QnaSettingsInput } from '@/types/management/qna';
import { QnaIcon } from './QnaIcon';
import styles from '@/styles/management/Qna.module.css';

interface Props {
  context: QnaContext;
  busy: boolean;
  onSave: (input: QnaSettingsInput) => void;
}

export function QnaSettings({ context, busy, onSave }: Props) {
  const [enabled, setEnabled] = useState(context.enabled);
  const [anonymousAllowed, setAnonymousAllowed] = useState(context.anonymousAllowed);
  const [moderationEnabled, setModerationEnabled] = useState(context.moderationEnabled);
  const details = useRef<HTMLDetailsElement>(null);
  const trigger = useRef<HTMLElement>(null);
  const contentId = useId();

  useEffect(() => {
    if (!context.canManage || context.meetingStatus !== 'IN_PROGRESS') return;
    const closeOutside = (event: PointerEvent) => {
      if (
        details.current?.open &&
        event.target instanceof Node &&
        !details.current.contains(event.target)
      ) {
        details.current.open = false;
      }
    };
    document.addEventListener('pointerdown', closeOutside);
    return () => document.removeEventListener('pointerdown', closeOutside);
  }, [context.canManage, context.meetingStatus]);

  if (!context.canManage || context.meetingStatus !== 'IN_PROGRESS') return null;
  const changed =
    enabled !== context.enabled ||
    anonymousAllowed !== context.anonymousAllowed ||
    moderationEnabled !== context.moderationEnabled;

  return (
    <details
      ref={details}
      className={styles.settings}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && details.current?.open) {
          event.preventDefault();
          details.current.open = false;
          trigger.current?.focus({ preventScroll: true });
        }
      }}
    >
      <summary
        ref={trigger}
        role="button"
        aria-label="Cài đặt hỏi đáp"
        aria-controls={contentId}
        title="Cài đặt hỏi đáp"
      >
        <QnaIcon name="settings" />
      </summary>
      <form
        id={contentId}
        onSubmit={(event) => {
          event.preventDefault();
          if (!busy && changed)
            onSave({
              enabled,
              anonymousAllowed,
              moderationEnabled,
              expectedVersion: context.settingsVersion,
            });
        }}
      >
        <fieldset disabled={busy}>
          <legend className={styles.srOnly}>Cài đặt hỏi đáp của cuộc họp</legend>
          <label className={styles.checkbox}>
            <input
              type="checkbox"
              checked={enabled}
              onChange={(event) => setEnabled(event.target.checked)}
            />
            Mở hỏi đáp
          </label>
          <label className={styles.checkbox}>
            <input
              type="checkbox"
              checked={anonymousAllowed}
              onChange={(event) => setAnonymousAllowed(event.target.checked)}
            />
            Cho phép gửi ẩn danh
          </label>
          <label className={styles.checkbox}>
            <input
              type="checkbox"
              checked={moderationEnabled}
              onChange={(event) => setModerationEnabled(event.target.checked)}
            />
            Duyệt câu hỏi trước khi hiển thị
          </label>
          <p className={styles.hint}>
            Tắt duyệt không tự công bố các câu hỏi đang chờ. Bạn vẫn cần duyệt từng câu hỏi đã gửi.
          </p>
          <button
            type="submit"
            className={styles.primary}
            disabled={busy || !changed}
            aria-busy={busy}
          >
            Lưu cài đặt hỏi đáp
          </button>
        </fieldset>
      </form>
    </details>
  );
}
