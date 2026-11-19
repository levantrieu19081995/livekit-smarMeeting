import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { QnaQuestionList } from './QnaQuestionList';
import { QnaSettings } from './QnaSettings';
import { QnaDeleteDialog } from './QnaDeleteDialog';
import type { QnaContext, QnaQuestion } from '@/types/management/qna';

const context: QnaContext = {
  enabled: true,
  anonymousAllowed: true,
  moderationEnabled: true,
  settingsVersion: 7,
  meetingStatus: 'IN_PROGRESS',
  canManage: true,
  canAsk: true,
  canUpvote: true,
};
const question: QnaQuestion = {
  id: 9,
  clientQuestionId: null,
  content: 'Câu hỏi cần quản lý',
  anonymous: true,
  authorDisplayName: 'Tên không được công bố',
  status: 'PENDING',
  createdAt: '2026-10-06T10:00:00+07:00',
  updatedAt: '2026-10-06T10:00:00+07:00',
  answeredAt: null,
  answer: null,
  answerAuthorDisplayName: null,
  upvoteCount: 0,
  upvotedByMe: false,
  mine: false,
  version: 4,
};
const list = (status: QnaQuestion['status'], overrides: Partial<QnaContext> = {}) =>
  renderToStaticMarkup(
    <QnaQuestionList
      questions={[{ ...question, status }]}
      context={{ ...context, ...overrides }}
      busy={false}
      onUpvote={() => {}}
      onModerate={() => {}}
      onAnswer={async () => true}
    />,
  );

describe('Moderator authority, lifecycle and delete confirmation', () => {
  it('offers approval for pending items and a reply form trigger for open items', () => {
    const pending = list('PENDING');
    expect(pending).toContain('aria-label="Duyệt câu hỏi 9"');
    expect(pending).not.toContain('aria-label="Trả lời câu hỏi 9"');
    const open = list('OPEN');
    expect(open).toContain('aria-label="Trả lời câu hỏi 9"');
    expect(open).not.toContain('aria-label="Duyệt câu hỏi 9"');
  });

  it('offers hide/delete for answered items, delete for hidden items and no actions for deleted ones', () => {
    expect(list('ANSWERED')).toContain('Thêm câu trả lời');
    expect(list('ANSWERED')).toContain('aria-label="Ẩn câu hỏi 9"');
    expect(list('HIDDEN')).toContain('aria-label="Xóa câu hỏi 9"');
    expect(list('HIDDEN')).not.toContain('aria-label="Ẩn câu hỏi 9"');
    expect(list('DELETED')).not.toContain('Quản lý câu hỏi');
  });

  it('keeps moderation available while Q&A is disabled but removes it after role loss or meeting end', () => {
    expect(list('OPEN', { enabled: false, canAsk: false, canUpvote: false })).toContain(
      'Quản lý câu hỏi',
    );
    expect(list('OPEN', { canManage: false })).not.toContain('Quản lý câu hỏi');
    expect(list('OPEN', { meetingStatus: 'COMPLETED' })).not.toContain('Quản lý câu hỏi');
  });

  it('never reveals an anonymous name to moderators', () => {
    const output = list('PENDING');
    expect(output).toContain('Ẩn danh');
    expect(output).not.toContain('Tên không được công bố');
  });

  it('explains that disabling moderation retains existing pending questions', () => {
    const output = renderToStaticMarkup(
      <QnaSettings context={context} busy={false} onSave={() => {}} />,
    );
    expect(output).toContain('Tắt duyệt không tự công bố');
    expect(output).toContain('Mở hỏi đáp');
    expect(output).toContain('Cho phép gửi ẩn danh');
    expect(output).toContain('Duyệt câu hỏi trước khi hiển thị');
  });

  it.each([{ canManage: false }, { meetingStatus: 'COMPLETED' as const }])(
    'does not expose settings without live manager permission: %j',
    (overrides) => {
      expect(
        renderToStaticMarkup(
          <QnaSettings context={{ ...context, ...overrides }} busy={false} onSave={() => {}} />,
        ),
      ).toBe('');
    },
  );

  it('requires an explicit accessible confirmation instead of deleting from the list button', () => {
    const output = renderToStaticMarkup(
      <QnaDeleteDialog question={question} onConfirm={() => {}} onCancel={() => {}} />,
    );
    expect(output).toContain('role="alertdialog"');
    expect(output).toContain('aria-modal="true"');
    expect(output).toContain('Xác nhận xóa');
    expect(output).toContain('Giữ câu hỏi');
    expect(output).not.toContain('Tên không được công bố');
  });
});
