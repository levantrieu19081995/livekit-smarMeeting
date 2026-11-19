'use client';

import { useEffect, useId, useRef } from 'react';
import Link from 'next/link';
import toast from 'react-hot-toast';
import type { ManagementMeeting } from '@/types/management/meeting';
import styles from '@/styles/management/MeetingCreation.module.css';

export function CreatedMeetingDialog({
  meeting,
  onClose,
}: {
  meeting: ManagementMeeting;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const headingId = useId();
  const link =
    typeof window === 'undefined'
      ? (meeting.meetingUrl ?? `/join/${encodeURIComponent(meeting.id)}`)
      : `${window.location.origin}/join/${encodeURIComponent(meeting.id)}`;
  useEffect(() => {
    const current = dialog.current;
    current?.showModal();
    return () => {
      if (current?.open) current.close();
    };
  }, []);

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(link);
      toast.success('Đã sao chép liên kết cuộc họp.');
    } catch {
      toast.error('Không thể sao chép. Bạn có thể chọn và sao chép liên kết bên dưới.');
    }
  }

  return (
    <dialog
      ref={dialog}
      className={styles.dialog}
      aria-labelledby={headingId}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <div className={styles.dialogHeading}>
        <h2 id={headingId}>Đã tạo cuộc họp</h2>
        <button
          type="button"
          className={styles.close}
          aria-label="Đóng thông tin cuộc họp"
          onClick={onClose}
        >
          ×
        </button>
      </div>
      <p>Gửi liên kết này cho những người bạn muốn mời tham gia.</p>
      <div className={styles.meetingInfo}>
        <strong>{meeting.title}</strong>
        {meeting.description ? <p>{meeting.description}</p> : null}
        <span>
          {meeting.accessType === 'PRIVATE'
            ? 'Riêng tư · Người trong danh sách mời'
            : meeting.creationMode === 'POINT'
              ? `Điểm cầu · ${meeting.points?.length ?? 0} điểm cầu`
              : meeting.creationMode === 'LATER'
                ? 'Tự do · Có thể sử dụng liên kết sau'
                : 'Tự do · Có thể vào họp ngay'}
        </span>
      </div>
      <label className={styles.field}>
        <span>Liên kết tham gia</span>
        <div className={styles.linkRow}>
          <input value={link} readOnly onFocus={(event) => event.currentTarget.select()} />
          <button
            type="button"
            className={styles.copy}
            onClick={() => void copyLink()}
            aria-label="Sao chép liên kết"
          >
            <svg
              width="20"
              height="20"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.7"
              aria-hidden="true"
            >
              <rect x="8" y="8" width="12" height="12" rx="2" />
              <path d="M16 8V4H4v12h4" />
            </svg>
          </button>
        </div>
      </label>
      <div className={styles.actions}>
        <Link className={styles.secondary} href={`/management/meetings/${meeting.id}`}>
          Xem chi tiết
        </Link>
        <Link className={styles.primary} href={`/join/${meeting.id}`}>
          Vào họp
        </Link>
      </div>
    </dialog>
  );
}
