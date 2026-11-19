'use client';

import { useId, useState, type FormEvent } from 'react';
import type { ManagementMeetingJoinInfo } from '@/types/management/join';
import styles from '@/styles/management/PointJoin.module.css';

export function ManagementPointJoin({
  meeting,
  error,
  initialPointId,
  onSubmit,
  onRefresh,
}: {
  meeting: ManagementMeetingJoinInfo;
  error?: string | null;
  initialPointId?: number | null;
  onSubmit: (pointId: number) => void;
  onRefresh: () => void;
}) {
  const [pointId, setPointId] = useState(initialPointId == null ? '' : String(initialPointId));
  const id = useId();
  const points = meeting.points ?? [];
  const selected = points.find((point) => String(point.id) === pointId);
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (selected) onSubmit(selected.id);
  }
  return (
    <main className={styles.page}>
      <form className={styles.card} onSubmit={submit} aria-labelledby={`${id}-title`}>
        <p className={styles.eyebrow}>Cuộc họp điểm cầu</p>
        <h1 id={`${id}-title`}>Chọn điểm cầu của bạn</h1>
        <p className={styles.description}>{meeting.title ?? 'Cuộc họp'}</p>
        <label className={styles.field} htmlFor={id}>
          <span>Điểm cầu *</span>
          <select
            id={id}
            value={pointId}
            onChange={(event) => setPointId(event.target.value)}
            required
            autoFocus
          >
            <option value="">Chọn điểm cầu</option>
            {points.map((point) => (
              <option key={point.id} value={point.id}>
                {point.name}
              </option>
            ))}
          </select>
        </label>
        {error ? (
          <p className={styles.error} role="alert">
            {error}
          </p>
        ) : null}
        {points.length === 0 ? (
          <>
            <p className={styles.description}>
              Cuộc họp chưa có điểm cầu khả dụng. Vui lòng liên hệ người tạo cuộc họp hoặc tải lại
              danh sách.
            </p>
            <button type="button" className={styles.secondary} onClick={onRefresh}>
              Tải lại danh sách
            </button>
          </>
        ) : null}
        <button type="submit" className={styles.submit} disabled={!selected}>
          Tiếp tục
        </button>
      </form>
    </main>
  );
}
