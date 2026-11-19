'use client';

import { useState } from 'react';
import type { QnaContext, QnaModerationAction, QnaQuestion } from '@/types/management/qna';
import { QNA_STATUS_LABELS } from '@/types/management/qna';
import { QnaAnswerForm } from './QnaAnswerForm';
import styles from '@/styles/management/Qna.module.css';

interface Props {
  questions: QnaQuestion[];
  context: QnaContext;
  busy: boolean;
  onUpvote: (question: QnaQuestion) => void;
  onModerate?: (question: QnaQuestion, action: Exclude<QnaModerationAction, 'ANSWER'>) => void;
  onAnswer?: (question: QnaQuestion, answer: string) => Promise<boolean>;
}

export function QnaQuestionList({
  questions,
  context,
  busy,
  onUpvote,
  onModerate,
  onAnswer,
}: Props) {
  const [answeringId, setAnsweringId] = useState<number | null>(null);
  const visible = questions.filter(
    (question) =>
      context.canManage ||
      question.status === 'OPEN' ||
      question.status === 'ANSWERED' ||
      (question.status === 'PENDING' && question.mine),
  );
  if (!visible.length) return <p className={styles.empty}>Chưa có câu hỏi trong danh sách này.</p>;

  return (
    <ol className={styles.questions} aria-label="Danh sách câu hỏi">
      {visible.map((question) => {
        const canUpvote =
          context.canUpvote &&
          context.enabled &&
          context.meetingStatus === 'IN_PROGRESS' &&
          (question.status === 'OPEN' || question.status === 'ANSWERED');
        return (
          <li key={question.id}>
            <article className={styles.question} aria-label={`Câu hỏi ${question.id}`}>
              <div className={styles.metadata}>
                <span className={styles.status} data-status={question.status}>
                  {QNA_STATUS_LABELS[question.status]}
                </span>
                {question.mine ? <span className={styles.mine}>Câu hỏi của bạn</span> : null}
              </div>
              <p className={styles.content}>{question.content}</p>
              <div className={styles.author}>
                <span>{question.anonymous ? 'Ẩn danh' : question.authorDisplayName}</span>
                <time dateTime={question.createdAt}>
                  {new Date(question.createdAt).toLocaleString('vi-VN')}
                </time>
              </div>
              {question.answer ? (
                <div
                  className={styles.answer}
                  aria-label={`Câu trả lời cho câu hỏi ${question.id}`}
                >
                  <h5>Câu trả lời</h5>
                  <p className={styles.content}>{question.answer}</p>
                  <div className={styles.author}>
                    <span>{question.answerAuthorDisplayName ?? 'Người tham gia'}</span>
                    {question.answeredAt ? (
                      <time dateTime={question.answeredAt}>
                        {new Date(question.answeredAt).toLocaleString('vi-VN')}
                      </time>
                    ) : null}
                  </div>
                </div>
              ) : null}
              <div className={styles.questionFooter}>
                {canUpvote ? (
                  <button
                    type="button"
                    className={question.upvotedByMe ? styles.upvoted : styles.upvote}
                    aria-label={`${question.upvotedByMe ? 'Bỏ ủng hộ' : 'Ủng hộ'} câu hỏi ${question.id}`}
                    aria-pressed={question.upvotedByMe}
                    disabled={busy}
                    onClick={() => onUpvote(question)}
                  >
                    {question.upvotedByMe ? 'Đã ủng hộ' : 'Ủng hộ'} · {question.upvoteCount}
                  </button>
                ) : (
                  <span className={styles.hint}>{question.upvoteCount} lượt ủng hộ</span>
                )}
                {question.answeredAt && !question.answer ? (
                  <span className={styles.hint}>
                    Trả lời lúc {new Date(question.answeredAt).toLocaleString('vi-VN')}
                  </span>
                ) : null}
              </div>
              {context.canManage &&
              context.meetingStatus === 'IN_PROGRESS' &&
              (onModerate || onAnswer) &&
              question.status !== 'DELETED' ? (
                <div
                  className={styles.moderationActions}
                  aria-label={`Quản lý câu hỏi ${question.id}`}
                >
                  {onModerate && question.status === 'PENDING' ? (
                    <button
                      type="button"
                      className={styles.secondary}
                      disabled={busy}
                      aria-label={`Duyệt câu hỏi ${question.id}`}
                      onClick={() => onModerate(question, 'APPROVE')}
                    >
                      Duyệt
                    </button>
                  ) : null}
                  {onAnswer && (question.status === 'OPEN' || question.status === 'ANSWERED') ? (
                    <button
                      type="button"
                      className={styles.secondary}
                      disabled={busy}
                      aria-label={`${question.answer ? 'Sửa câu trả lời cho' : 'Trả lời'} câu hỏi ${question.id}`}
                      aria-expanded={answeringId === question.id}
                      aria-controls={`qna-answer-${question.id}`}
                      onClick={() => setAnsweringId(question.id)}
                    >
                      {question.answer
                        ? 'Sửa câu trả lời'
                        : question.status === 'ANSWERED'
                          ? 'Thêm câu trả lời'
                          : 'Trả lời'}
                    </button>
                  ) : null}
                  {onModerate && question.status !== 'HIDDEN' ? (
                    <button
                      type="button"
                      className={styles.secondary}
                      disabled={busy}
                      aria-label={`Ẩn câu hỏi ${question.id}`}
                      onClick={() => onModerate(question, 'HIDE')}
                    >
                      Ẩn
                    </button>
                  ) : null}
                  {onModerate ? (
                    <button
                      type="button"
                      className={styles.danger}
                      disabled={busy}
                      aria-label={`Xóa câu hỏi ${question.id}`}
                      onClick={() => onModerate?.(question, 'DELETE')}
                    >
                      Xóa
                    </button>
                  ) : null}
                </div>
              ) : null}
              {answeringId === question.id &&
              onAnswer &&
              context.canManage &&
              context.meetingStatus === 'IN_PROGRESS' &&
              (question.status === 'OPEN' || question.status === 'ANSWERED') ? (
                <QnaAnswerForm
                  question={question}
                  busy={busy}
                  onSave={(answer) => onAnswer(question, answer)}
                  onCancel={() => setAnsweringId(null)}
                />
              ) : null}
            </article>
          </li>
        );
      })}
    </ol>
  );
}
