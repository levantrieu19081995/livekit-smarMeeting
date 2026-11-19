import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { QnaQuestionList } from './QnaQuestionList';
import type { QnaContext, QnaQuestion } from '@/types/management/qna';

const context: QnaContext = {
  enabled: true,
  anonymousAllowed: true,
  moderationEnabled: true,
  settingsVersion: 0,
  meetingStatus: 'IN_PROGRESS',
  canManage: false,
  canAsk: true,
  canUpvote: true,
};
const question: QnaQuestion = {
  id: 9,
  clientQuestionId: null,
  content: 'Kế hoạch triển khai?',
  anonymous: false,
  authorDisplayName: 'Nguyễn Văn A',
  status: 'OPEN',
  createdAt: '2026-10-06T10:00:00+07:00',
  updatedAt: '2026-10-06T10:00:00+07:00',
  answeredAt: null,
  answer: null,
  answerAuthorDisplayName: null,
  upvoteCount: 3,
  upvotedByMe: false,
  mine: false,
  version: 0,
};
const markup = (questions: QnaQuestion[], overrides: Partial<QnaContext> = {}) =>
  renderToStaticMarkup(
    <QnaQuestionList
      questions={questions}
      context={{ ...context, ...overrides }}
      busy={false}
      onUpvote={() => {}}
    />,
  );

describe('Question visibility and server-confirmed upvotes', () => {
  it('conceals anonymous identity even in moderator views and never renders the deduplication ID', () => {
    const output = markup(
      [{ ...question, anonymous: true, clientQuestionId: 'private-client-id', mine: true }],
      { canManage: true },
    );
    expect(output).toContain('Ẩn danh');
    expect(output).not.toContain('Nguyễn Văn A');
    expect(output).not.toContain('private-client-id');
  });

  it('only shows own pending questions to participants and hides hidden/deleted items', () => {
    const output = markup([
      { ...question, id: 1, status: 'PENDING', mine: true, content: 'Câu đang chờ của tôi' },
      { ...question, id: 2, status: 'PENDING', content: 'Câu chưa duyệt của người khác' },
      { ...question, id: 3, status: 'HIDDEN', content: 'Nội dung bị ẩn' },
      { ...question, id: 4, status: 'DELETED', content: 'Nội dung đã xóa' },
    ]);
    expect(output).toContain('Câu đang chờ của tôi');
    expect(output).not.toContain('Câu chưa duyệt của người khác');
    expect(output).not.toContain('Nội dung bị ẩn');
    expect(output).not.toContain('Nội dung đã xóa');
    expect(output).not.toContain('aria-pressed');
  });

  it('permits own and answered question upvotes with the persisted pressed state/count', () => {
    const output = markup([
      { ...question, status: 'ANSWERED', mine: true, upvotedByMe: true, upvoteCount: 7 },
    ]);
    expect(output).toContain('aria-pressed="true"');
    expect(output).toContain('Đã ủng hộ');
    expect(output).toContain('7');
  });

  it.each([
    { canUpvote: false },
    { enabled: false },
    { meetingStatus: 'COMPLETED' as const },
    { meetingStatus: 'CANCELLED' as const },
  ])('renders read-only counts when the backend denies writes: %j', (overrides) => {
    const output = markup([question], overrides);
    expect(output).toContain('3 lượt ủng hộ');
    expect(output).not.toContain('<button');
  });

  it('renders submitted content as text rather than executing markup', () => {
    const output = markup([{ ...question, content: '<script>alert("question")</script>' }]);
    expect(output).toContain('&lt;script&gt;');
    expect(output).not.toContain('<script>');
  });

  it('keeps written answers and responder visible in ended history, safely rendering plain text', () => {
    const output = markup(
      [
        {
          ...question,
          status: 'ANSWERED',
          answer: '<script>answer</script>\nDòng hai',
          answerAuthorDisplayName: 'Người trả lời',
          answeredAt: '2026-10-07T10:00:00+07:00',
        },
      ],
      { meetingStatus: 'COMPLETED' },
    );
    expect(output).toContain('Câu trả lời');
    expect(output).toContain('&lt;script&gt;answer&lt;/script&gt;');
    expect(output).toContain('Người trả lời');
    expect(output).not.toContain('<script>');
    expect(output).not.toContain('<button');
  });
});
