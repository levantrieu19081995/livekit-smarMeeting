# Ghi nhận chức năng thư mời email và RSVP - để triển khai sau

Ngày ghi nhận: 08/10/2026 (Asia/Bangkok).

## Trạng thái

- Đã đọc đặc tả và audit kiến trúc MAIL P0 ở chế độ chỉ đọc.
- Người dùng yêu cầu ghi nhận để làm sau; chưa triển khai, chưa gửi email,
  chưa thay đổi code ứng dụng, cấu hình mail hoặc database.
- Công việc hiện tại quay lại các rủi ro production đã phân tích trước đó.

## Phạm vi đã được người dùng xác nhận

- Áp dụng cho tạo cuộc họp Điểm cầu (POINT) và Riêng tư (PRIVATE).
- Chỉ gửi riêng cho tài khoản được chọn rõ ràng trong danh sách người tham gia.
- Chưa bổ sung email đại diện cho từng điểm cầu hay người nhận email tự do.
- Không tự gửi cho người vào bằng link, khách, hay người tạo được hệ thống tự thêm.
- Tùy chọn gửi thư có thể bật/tắt; bộ xử lý nền gửi sau khi lưu cuộc họp thành công.
- Theo đặc tả có thư mời, RSVP ba lựa chọn, lịch .ics, thống kê, gửi lại thất bại,
  thông báo sửa/hủy và thêm/xóa người được mời.
- RSVP là phản hồi dự kiến tham dự, không phải quyền vào phòng hay xác nhận đã có mặt.

## Điểm cần xử lý khi triển khai

1. Durable outbox/invitation cùng transaction dữ liệu cuộc họp; không gọi SMTP trong HTTP create.
2. Lưu membership người nhận được chọn riêng; lấy email authoritative từ tài khoản backend.
3. Bổ sung chống tạo trùng PRIVATE; giữ cờ mail trong POINT fingerprint, receipt và khôi phục form.
4. PRIVATE hiện sửa metadata và roster bằng nhiều request; cần thiết kế sự kiện nhất quán.
5. Snapshot từng event, deduplication, thứ tự revision và chính sách bỏ job lỗi thời.
6. Giữ đủ dữ liệu cancellation sau hard delete; kết thúc bình thường khác hủy.
7. Worker riêng, concurrency giới hạn, timeout, backoff, lease và phục hồi restart;
   không cam kết SMTP gửi đúng một lần trong mọi sự cố.
8. RSVP token có hạn, được bảo vệ/thu hồi; GET không thay đổi phản hồi,
   POST giữ CSRF; PRIVATE join vẫn dùng quyền hiện có.
9. Calendar UID ổn định, SEQUENCE và múi giờ rõ; .ics không tự đồng bộ
   phản hồi từ Google/Outlook về SmartMeeting.
10. Quyền xem danh sách/gửi lại dùng quyền quản lý cuộc họp hiện tại,
    không suy từ quyền duyệt phát biểu POINT.

## Đề xuất chưa phải xác nhận nghiệp vụ

- Nếu tạo với gửi mail tắt, các lần sửa sau không tự bật gửi.
- Khi đổi lịch, giữ dấu vết phiên bản mà người nhận đã phản hồi và hiển thị rõ
  phản hồi trước lần đổi lịch; không tự hiểu đã đồng ý lịch mới.
- Chính sách hết hạn token, xác nhận lại, retry không chắc đã gửi và gửi thông
  báo cho người đã từ chối cần khóa khi bắt đầu triển khai.

## Tài liệu gốc

- Đặc tả người dùng cung cấp:
  `C:/Users/cuscsoft/.codex/attachments/99a8d9b9-b66f-4c90-8ced-5119f0c83cf8/Pasted text.txt`.
- Tên đặc tả: Meeting Email Notification & RSVP, các giai đoạn MAIL P0-P4.

Ghi nhận này không đồng nghĩa chức năng đã được triển khai hoặc nghiệm thu.
