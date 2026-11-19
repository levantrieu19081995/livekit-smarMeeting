import { describe, expect, it } from 'vitest';
import { ManagementApiError } from './httpClient';
import { qnaErrorMessage } from './qnaErrorMessage';

describe('Q&A user-facing errors', () => {
  it.each([
    [
      'QA_CONNECTION_NOT_CONNECTED',
      'Phiên tham gia chưa được xác nhận đang kết nối. Hãy kiểm tra trạng thái vào phòng, rồi tải lại Hỏi đáp.',
    ],
    [
      'QA_PARTICIPANT_IDENTITY_MISMATCH',
      'Tài khoản đăng nhập hiện tại không khớp với phiên tham gia phòng. Hãy rời phòng và tham gia lại bằng tài khoản hiện tại.',
    ],
    [
      'QA_INVALID_PARTICIPANT_TOKEN',
      'Không thể xác thực phiên tham gia phòng họp. Hãy rời phòng và tham gia lại, rồi tải lại Hỏi đáp.',
    ],
    [
      'QA_PARTICIPANT_TOKEN_REQUIRED',
      'Không thể xác thực phiên tham gia phòng họp. Hãy rời phòng và tham gia lại, rồi tải lại Hỏi đáp.',
    ],
    [
      'QA_ACCOUNT_DISABLED',
      'Tài khoản của bạn hiện không hoạt động nên không có quyền sử dụng Hỏi đáp.',
    ],
    [
      'QA_PARTICIPANT_REMOVED',
      'Bạn đã bị gỡ khỏi danh sách người tham gia cuộc họp nên không có quyền sử dụng Hỏi đáp.',
    ],
  ])('explains only the known denied Q&A code %s', (code, expected) => {
    expect(qnaErrorMessage(new ManagementApiError('Thông báo quyền chung.', 403, code))).toBe(
      expected,
    );
  });

  it.each([
    undefined,
    'QA_ACCESS_FORBIDDEN',
    'MEETING_ACCESS_DENIED',
    'CONNECTION_NOT_CONNECTED',
    'qa_connection_not_connected',
    'QA_CONNECTION_NOT_CONNECTED ',
    'constructor',
    '__proto__',
  ])('keeps unknown or inexact permission codes controlled: %s', (code) => {
    expect(
      qnaErrorMessage(new ManagementApiError('Thông báo backend cần được giữ nguyên.', 403, code)),
    ).toBe('Thông báo backend cần được giữ nguyên.');
  });

  it.each([400, 401, 404, 409, 500])(
    'does not rewrite another HTTP status %i even with a known Q&A code',
    (status) => {
      expect(
        qnaErrorMessage(
          new ManagementApiError(
            'Thông báo backend cho trạng thái khác.',
            status,
            'QA_CONNECTION_NOT_CONNECTED',
          ),
        ),
      ).toBe('Thông báo backend cho trạng thái khác.');
    },
  );

  it('does not claim automatic availability for an unconnected session', () => {
    const message = qnaErrorMessage(
      new ManagementApiError('403', 403, 'QA_CONNECTION_NOT_CONNECTED'),
    );
    expect(message).not.toMatch(/sắp|sẽ tự mở|chỉ cần chờ|thành công/i);
    expect(message).toContain('kiểm tra trạng thái vào phòng');
  });

  it.each([400, 401, 403, 409])('keeps the controlled backend message for status %i', (status) => {
    expect(
      qnaErrorMessage(new ManagementApiError('Cuộc họp không cho phép hỏi ẩn danh.', status)),
    ).toBe('Cuộc họp không cho phép hỏi ẩn danh.');
  });

  it('keeps confirmation-timeout advice so users inspect server state before retrying', () => {
    const advice = 'Máy chủ chưa xác nhận kịp thời. Hãy tải lại để kiểm tra trước khi thử lại.';
    expect(qnaErrorMessage(new Error(advice))).toBe(advice);
  });

  it.each([
    new Error(
      'Không thể kết nối Java meeting-service tại 127.0.0.1:8080 MEETING_SERVICE_INTERNAL_URL',
    ),
    new TypeError('Failed to fetch internal proxy'),
    'raw internal error',
    null,
  ])('replaces unexpected errors with network recovery guidance', (failure) => {
    const message = qnaErrorMessage(failure);
    expect(message).toContain('Hãy kiểm tra mạng và tải lại hỏi đáp.');
    expect(message).not.toMatch(/Java|127\.0\.0\.1|proxy|MEETING_SERVICE_INTERNAL_URL/);
  });
});
