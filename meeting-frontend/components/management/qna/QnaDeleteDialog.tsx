'use client';

import { useEffect, useId, useRef } from 'react';
import type { QnaQuestion } from '@/types/management/qna';
import styles from '@/styles/management/Qna.module.css';

interface Props {
  question: QnaQuestion;
  onConfirm: () => void;
  onCancel: () => void;
}

export function QnaDeleteDialog({ question, onConfirm, onCancel }: Props) {
  const titleId = useId();
  const descriptionId = useId();
  const dialog = useRef<HTMLDivElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const previous = document.activeElement;
    cancel.current?.focus();
    return () => {
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
    };
  }, []);

  return (
    <div className={styles.dialogBackdrop}>
      <div
        className={styles.dialog}
        ref={dialog}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.preventDefault();
            onCancel();
          }
          if (event.key === 'Tab') {
            const buttons = dialog.current?.querySelectorAll<HTMLButtonElement>('button');
            if (!buttons?.length) return;
            const first = buttons[0];
            const last = buttons[buttons.length - 1];
            if (event.shiftKey && document.activeElement === first) {
              event.preventDefault();
              last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
              event.preventDefault();
              first.focus();
            }
          }
        }}
      >
        <h4 id={titleId}>Xóa câu hỏi?</h4>
        <p id={descriptionId}>
          Câu hỏi sẽ không còn hiển thị cho người tham gia. Thao tác này cần được bạn xác nhận.
        </p>
        <p className={styles.dialogContent}>{question.content}</p>
        <div className={styles.moderationActions}>
          <button type="button" ref={cancel} className={styles.secondary} onClick={onCancel}>
            Giữ câu hỏi
          </button>
          <button
            type="button"
            className={styles.danger}
            onClick={onConfirm}
            aria-label={`Xác nhận xóa câu hỏi ${question.id}`}
          >
            Xác nhận xóa
          </button>
        </div>
      </div>
    </div>
  );
}
