'use client';

import { useId } from 'react';
import { parseMeetingPointNames } from '@/lib/meetingPointRules';
import styles from '@/styles/management/MeetingPointFields.module.css';

export function MeetingPointFields({
  value,
  error,
  disabled,
  readOnly = false,
  onChange,
}: {
  value: string;
  error?: string;
  disabled: boolean;
  readOnly?: boolean;
  onChange: (value: string) => void;
}) {
  const fieldId = useId();
  const names = parseMeetingPointNames(value);
  return (
    <section className={styles.section} aria-labelledby={`${fieldId}-title`}>
      <h3 id={`${fieldId}-title`}>Danh sách điểm cầu</h3>
      <p>
        {readOnly
          ? 'Danh sách được giữ nguyên sau khi có người vào họp để bảo toàn lịch sử phát biểu.'
          : 'Nhập mỗi điểm cầu trên một dòng. Người tham gia sẽ chọn điểm cầu khi vào họp.'}
      </p>
      <label className={styles.field} htmlFor={fieldId}>
        <span>Tên điểm cầu *</span>
        <textarea
          id={fieldId}
          rows={5}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder={'Điểm cầu Trường A\nĐiểm cầu Trường B\nĐiểm cầu Trường C'}
          disabled={disabled}
          readOnly={readOnly}
          aria-invalid={Boolean(error)}
          aria-describedby={`${fieldId}-help${error ? ` ${fieldId}-error` : ''}`}
        />
      </label>
      <p id={`${fieldId}-help`} className={styles.help}>
        {names.length}/100 điểm cầu · Tối đa 150 ký tự mỗi tên
      </p>
      {error ? (
        <p id={`${fieldId}-error`} className={styles.error} role="alert">
          {error}
        </p>
      ) : null}
    </section>
  );
}
