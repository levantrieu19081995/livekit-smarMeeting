'use client';

import { useState, type FormEvent } from 'react';
import type { ManagementMeeting } from '@/types/management/meeting';
import type { MeetingFormValues } from './MeetingForm';
import styles from '@/styles/management/MeetingCreation.module.css';

interface Props {
  meeting?: ManagementMeeting;
  submitting: boolean;
  onSubmit: (values: MeetingFormValues) => Promise<void>;
  onCancel: () => void;
  initialValues?: MeetingFormValues;
}

export function QuickMeetingForm({
  meeting,
  submitting,
  onSubmit,
  onCancel,
  initialValues,
}: Props) {
  const [title, setTitle] = useState(meeting?.title ?? initialValues?.title ?? '');
  const [description, setDescription] = useState(
    meeting?.description ?? initialValues?.description ?? '',
  );
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    void onSubmit({
      title: title.trim(),
      description: description.trim(),
      accessType: 'PUBLIC',
      creationMode: meeting?.creationMode ?? 'LATER',
      startTime: meeting?.startTime ?? null,
      endTime: meeting?.endTime ?? null,
      participants: meeting?.participants ?? [],
      documents: meeting?.documents ?? [],
    });
  }
  return (
    <form className={styles.form} onSubmit={submit}>
      <h1>{meeting ? 'Hiệu chỉnh cuộc họp' : 'Tạo cuộc họp để sử dụng sau'}</h1>
      <p>
        {meeting
          ? 'Cập nhật tên và mô tả cuộc họp.'
          : 'Tạo liên kết và chia sẻ khi bạn muốn bắt đầu cuộc họp.'}
      </p>
      <label className={styles.field}>
        <span>Tên cuộc họp{meeting ? '' : ' (không bắt buộc)'}</span>
        <input
          value={title}
          maxLength={255}
          required={Boolean(meeting)}
          disabled={submitting}
          placeholder="Tên cuộc họp của bạn"
          onChange={(event) => setTitle(event.target.value)}
        />
      </label>
      <label className={styles.field}>
        <span>Nội dung / Mô tả</span>
        <textarea
          value={description}
          rows={5}
          maxLength={20000}
          disabled={submitting}
          placeholder="Nhập nội dung hoặc mô tả cuộc họp…"
          onChange={(event) => setDescription(event.target.value)}
        />
      </label>
      <div className={styles.actions}>
        <button type="button" className={styles.secondary} disabled={submitting} onClick={onCancel}>
          Hủy
        </button>
        <button type="submit" className={styles.primary} disabled={submitting}>
          {submitting ? 'Đang lưu…' : meeting ? 'Lưu hiệu chỉnh' : 'Tạo liên kết cuộc họp'}
        </button>
      </div>
    </form>
  );
}
