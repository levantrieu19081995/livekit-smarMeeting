import { ManagementApiError } from './httpClient';

const CONFIRMATION_TIMEOUT =
  'Máy chủ chưa xác nhận kịp thời. Hãy tải lại để kiểm tra trước khi thử lại.';

export function qnaErrorMessage(failure: unknown): string {
  if (failure instanceof ManagementApiError) {
    if (failure.status !== 403) return failure.message;
    switch (failure.code) {
      case 'QA_CONNECTION_NOT_CONNECTED':
        return 'Phiên tham gia chưa được xác nhận đang kết nối. Hãy kiểm tra trạng thái vào phòng, rồi tải lại Hỏi đáp.';
      case 'QA_PARTICIPANT_IDENTITY_MISMATCH':
        return 'Tài khoản đăng nhập hiện tại không khớp với phiên tham gia phòng. Hãy rời phòng và tham gia lại bằng tài khoản hiện tại.';
      case 'QA_INVALID_PARTICIPANT_TOKEN':
      case 'QA_PARTICIPANT_TOKEN_REQUIRED':
        return 'Không thể xác thực phiên tham gia phòng họp. Hãy rời phòng và tham gia lại, rồi tải lại Hỏi đáp.';
      case 'QA_ACCOUNT_DISABLED':
        return 'Tài khoản của bạn hiện không hoạt động nên không có quyền sử dụng Hỏi đáp.';
      case 'QA_PARTICIPANT_REMOVED':
        return 'Bạn đã bị gỡ khỏi danh sách người tham gia cuộc họp nên không có quyền sử dụng Hỏi đáp.';
      default:
        return failure.message;
    }
  }
  if (failure instanceof Error && failure.message === CONFIRMATION_TIMEOUT)
    return CONFIRMATION_TIMEOUT;
  return 'Không thể kết nối hệ thống. Hãy kiểm tra mạng và tải lại hỏi đáp.';
}
