'use client';

import { FormEvent, useId, useState } from 'react';
import type { ManagementMeetingJoinInfo } from '@/types/management/join';
import styles from '@/styles/management/GuestJoin.module.css';

interface ManagementGuestJoinProps {
  meeting: ManagementMeetingJoinInfo;
  submitting: boolean;
  error?: string | null;
  onSubmit: (displayName: string) => void;
}

export function ManagementGuestJoin({
  meeting,
  submitting,
  error,
  onSubmit,
}: ManagementGuestJoinProps) {
  const [displayName, setDisplayName] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);
  const errorId = useId();
  const titleId = useId();

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const trimmedName = displayName.trim();
    if (trimmedName.length < 2) {
      setValidationError('Họ và tên phải có ít nhất 2 ký tự');
      return;
    }

    setValidationError(null);
    onSubmit(trimmedName);
  }

  return (
    <main className={styles.page}>
      <div className={styles.frame}>
        <div className={styles.decoration} aria-hidden="true">
          <span className={styles.planetLeft} />
          <span className={styles.planetRight} />
          <span className={styles.orbitLeft} />
          <span className={styles.orbitRight} />
          <span className={styles.dotsLeft} />
          <span className={styles.dotsRight} />
        </div>
        <form
          className={styles.card}
          onSubmit={handleSubmit}
          aria-labelledby={titleId}
          aria-busy={submitting}
        >
          <p className={styles.eyebrow}>Khách tham gia</p>
          <h1 id={titleId} className={styles.title}>
            Tham gia cuộc họp
          </h1>
          <p className={styles.description}>
            Cuộc họp này cho phép khách tham gia. Vui lòng nhập họ tên.
          </p>
          <p className={styles.meetingName}>{meeting.title ?? 'Cuộc họp'}</p>

          <label className={styles.field}>
            <span>Họ và tên</span>
            <input
              value={displayName}
              onChange={(event) => {
                setDisplayName(event.target.value);
                setValidationError(null);
              }}
              placeholder="Nhập họ tên của bạn"
              maxLength={100}
              disabled={submitting}
              aria-invalid={Boolean(validationError)}
              aria-describedby={validationError || error ? errorId : undefined}
              autoFocus
            />
          </label>

          {validationError || error ? (
            <div id={errorId} className={styles.error} role="alert">
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" focusable="false">
                <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.6" />
                <path d="M12 7.5v5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
                <circle cx="12" cy="16" r="1" fill="currentColor" />
              </svg>
              <span>{validationError || error}</span>
            </div>
          ) : null}

          <button type="submit" className={styles.submit} disabled={submitting}>
            {submitting ? 'Đang chuẩn bị phòng...' : 'Tiếp tục'}
          </button>
        </form>
      </div>
    </main>
  );
}
