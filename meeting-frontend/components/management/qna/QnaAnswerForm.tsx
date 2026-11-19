'use client';

import { useRef, useState } from 'react';
import type { QnaQuestion } from '@/types/management/qna';
import styles from '@/styles/management/Qna.module.css';

interface Props {
  question: QnaQuestion;
  busy: boolean;
  onSave: (answer: string) => Promise<boolean>;
  onCancel: () => void;
}

export function QnaAnswerForm({ question, busy, onSave, onCancel }: Props) {
  const [initialAnswer] = useState(question.answer ?? '');
  const [answer, setAnswer] = useState(initialAnswer);
  const submitting = useRef(false);
  const changedOnServer = (question.answer ?? '') !== initialAnswer;
  const valid = !!answer.trim() && answer.trim().length <= 5000;

  return (
    <form
      className={`${styles.ask} ${styles.answerForm}`}
      onSubmit={async (event) => {
        event.preventDefault();
        if (busy || submitting.current || changedOnServer || !valid) return;
        submitting.current = true;
        try {
          if (await onSave(answer.trim())) onCancel();
        } finally {
          submitting.current = false;
        }
      }}
    >
      <div className={styles.askHeading}>
        <label htmlFor={`qna-answer-${question.id}`}>Câu trả lời của bạn</label>
        <span id={`qna-answer-length-${question.id}`} className={styles.hint}>
          {answer.length}/5.000
        </span>
      </div>
      <textarea
        autoFocus
        id={`qna-answer-${question.id}`}
        aria-describedby={`qna-answer-length-${question.id}`}
        placeholder="Nhập câu trả lời…"
        rows={3}
        maxLength={5000}
        value={answer}
        disabled={busy}
        onChange={(event) => setAnswer(event.target.value)}
      />
      <p className={styles.hint}>Câu trả lời hiển thị kèm tên của bạn.</p>
      {changedOnServer ? (
        <p className={styles.hint} role="status">
          Câu trả lời đã được cập nhật. Hãy đóng và mở lại ô trả lời để xem nội dung mới.
        </p>
      ) : null}
      <div className={styles.answerActions}>
        <button type="button" className={styles.secondary} disabled={busy} onClick={onCancel}>
          Hủy
        </button>
        <button
          type="submit"
          className={styles.primary}
          disabled={busy || changedOnServer || !valid}
          aria-busy={busy}
        >
          {busy ? 'Đang lưu…' : 'Lưu câu trả lời'}
        </button>
      </div>
    </form>
  );
}
