import { DisconnectReason } from 'livekit-client';

export function describeRoomDisconnect(reason?: DisconnectReason) {
  if (reason === DisconnectReason.ROOM_DELETED)
    return {
      message: 'Cuộc họp đã được kết thúc. Bạn có thể xem lại thông tin cuộc họp.',
      retry: false,
    };
  if (reason === DisconnectReason.PARTICIPANT_REMOVED)
    return {
      message: 'Phiên tham gia đã bị kết thúc bởi người quản lý cuộc họp.',
      retry: false,
    };
  if (reason === DisconnectReason.DUPLICATE_IDENTITY)
    return {
      message:
        'Phiên tham gia này đã được sử dụng ở một kết nối khác. Kiểm tra thiết bị của bạn trước khi vào lại.',
      retry: true,
    };
  return {
    message:
      'Kết nối cuộc họp đã bị gián đoạn. Kiểm tra mạng rồi nhấn Thử lại để xác nhận trạng thái và vào lại.',
    retry: true,
  };
}
