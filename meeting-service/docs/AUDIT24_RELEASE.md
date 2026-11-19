# Hướng dẫn triển khai bản sửa 24F

Bản sửa giữ các loại cuộc họp PUBLIC/PRIVATE/POINT, quyền người tạo/chủ tọa/thư ký đã chốt, hỏi đáp và biểu quyết mở cho người tham gia. Chức năng gửi email và việc tách thành ba ứng dụng Java độc lập vẫn để giai đoạn sau.

## Dữ liệu và hợp đồng được bổ sung

- Flyway V20: hàng đợi dọn LiveKit, thời điểm webhook, revision biên bản và chỉ mục chat.
- V21: hàng đợi dọn tập tin và dấu nhận diện nội dung để tránh đính kèm trùng khi thử lại.
- V22: phiên bản quyền của tài khoản và khóa cập nhật tài khoản, để phiên cũ không sống lại sau mở khóa/hạ quyền.
- Những migration V1–V19 giữ nguyên. Cần sao lưu database trước khi triển khai. Không chạy bản cũ với schema mới mà chưa đánh giá tương thích; việc khôi phục database phải cùng phiên bản ứng dụng.
- Frontend hiện gửi `clientRequestId` ổn định cho mọi loại tạo họp. API cũ không gửi mã vẫn được hỗ trợ ngoài POINT; đảm bảo thử lại không tạo trùng áp dụng khi dùng mã yêu cầu. Mã bị xóa trả 410; nội dung khác dùng lại cùng mã trả 409.
- Lưu thông tin và danh sách tham gia dùng một PUT. `rosterBaseline` giúp từ chối bản chỉnh sửa cũ khi vai trò/danh sách đã đổi; không loại nhầm người vừa vào sau lúc mở trang. API cũ không gửi baseline giữ khả năng tương thích.
- PUT bản nháp biên bản yêu cầu `expectedRevision`. GET và phản hồi cập nhật trả revision thực tế sau khi ghi. Luồng tạo phiên bản mới trên giao diện vẫn dùng `baseVersion`.
- Chat lưu trên Java trước khi phát thông báo. GET `/api/meetings/{id}/chat/messages` yêu cầu `X-Meeting-Participant-Token`; khách PUBLIC được đọc bằng đúng bằng chứng tham gia, PRIVATE vẫn kiểm tra đăng nhập và danh sách. Trang mới khôi phục 100 tin gần nhất và giữ tối đa 500 tin trong bộ nhớ; dữ liệu lịch sử trong database không bị cắt.

## Kết thúc, mất mạng và khởi động lại

- Kết thúc/hủy/xóa đều xử lý capture, biểu quyết và phát biểu. Khi cuộc họp có giờ kết thúc đã đến hạn, tác vụ nền đối chiếu trạng thái và dọn tài nguyên; cuộc họp không có giờ kết thúc vẫn kết thúc theo thao tác người dùng.
- Trạng thái terminal không được mở lại bằng API cập nhật thông thường. Công cụ quản trị vẫn giữ chức năng mở lại có chủ ý; yêu cầu này chờ hàng đợi dọn phòng cũ hoàn tất. Quyết định dọn tài nguyên của một lần join bị từ chối cùng khóa cuộc họp với thao tác mở lại, tránh đóng nhầm phòng vừa mở. Webhook muộn không cấp lại quyền cho cuộc họp terminal hoặc người đã bị loại; thứ tự sự kiện và phiên bản quyền tài khoản được kiểm tra.
- LiveKit lỗi không làm mất yêu cầu dọn phòng/loại người. Yêu cầu được ghi trong database và thử lại với backoff; hàng đợi tồn tại sau xóa cuộc họp hoặc restart. Phản hồi 202 và `X-Meeting-Cleanup-Pending:true` nghĩa là quyết định đã lưu nhưng dọn LiveKit còn chờ. Hủy bằng công cụ quản trị cũng đóng capture/biểu quyết/phát biểu và trả trạng thái dọn phòng này.
- INSTANT/LATER đã bắt đầu và không có hạn kết thúc giữ trạng thái đang họp khi đồng hồ hệ thống lùi. Giờ do hệ thống sinh được làm tròn xuống độ chính xác microsecond của PostgreSQL; bộ lọc danh sách và công cụ quản trị dùng cùng quy tắc trạng thái.
- LiveKit RPC có giới hạn thời gian; cấp token vào phòng mặc định 300 giây. Hết hạn token ban đầu không tự kết thúc kết nối media đã được thiết lập; bước chuẩn bị vào họp quá lâu cần lấy lại quyền qua thao tác thử lại.
- Capture lỗi được giữ FAILED cùng dữ liệu đã ghi; không đổi thành COMPLETED chỉ vì người dùng nhấn dừng. Java đối chiếu trạng thái runtime theo lịch và khi mở thông tin ghi biên bản. Phản hồi bắt đầu/dừng và tác vụ khôi phục cùng khóa trạng thái, tránh phản hồi muộn ghi đè kết quả mới.
- Python chỉ có một tiến trình sở hữu thư mục dữ liệu capture/STT. Không chạy nhiều worker/replica trên cùng dữ liệu theo thiết kế hiện tại; ứng dụng kiểm tra sở hữu và từ chối tiến trình thứ hai. Đây là giới hạn triển khai được áp dụng rõ ràng, không phải cơ chế chia tải nhiều máy.
- STT có giới hạn dùng chung cho toàn tiến trình, số job nhận vào và hàng đợi. Kết quả terminal/biên nhận được lưu bền; Java có tác vụ lấy lại status/result nếu callback bị mất.

## Điều kiện cấu hình

- Dùng profile Java production (`prod`) cùng các khóa nội bộ riêng, không dùng khóa mặc định/demo. Ngưỡng admission/rate được giới hạn trong từng tiến trình; nhiều replica cần giới hạn chung ở gateway/hạ tầng.
- Python production yêu cầu khóa nội bộ hợp lệ. Tắt kiểm tra khóa chỉ áp dụng khi chọn rõ môi trường development. Không đổi `.env` hiện dùng trong đợt sửa này.
- Các giá trị `livekit.join-token-ttl-seconds` được giới hạn 30–600, mặc định 300; `livekit.request-timeout-seconds` 1–30, mặc định 10.
- `meeting.terminal-recovery-enabled` mặc định true, chu kỳ mặc định 30 giây. Không tắt tác vụ khôi phục/dọn tài nguyên ở production nếu cần đảm bảo các trạng thái đã kết thúc được đối chiếu.
- Dọn tập tin sau xóa có hàng đợi bền và lease; tập tin mồ côi sau crash có khoảng chờ mặc định 24 giờ để tránh xóa nhầm upload đang xử lý. Cần bật tác vụ dọn và cấp quyền ghi/xóa thư mục upload cho đúng ứng dụng.
- Tài liệu HTML/SVG/khả thi hoặc giả MIME bị từ chối. PDF có nội dung chủ động, PDF mã hóa và Office được tải xuống thay vì chạy inline. Nội dung legacy không hỗ trợ vẫn có thể tải dưới dạng attachment; đây không phải xác nhận tập tin đã được quét virus.

## Những điều không suy ra từ kiểm thử code

- Kiểm thử 100 phiên khách logic qua HTTP với LiveKit giả lập không xác nhận 100 thiết bị truyền âm thanh/video ổn định hai giờ.
- Khóa tài khoản thu hồi phiên/API và quyền vào lại; không tự ngắt ngay luồng media SFU đã tồn tại. Muốn loại ngay khỏi phòng dùng chức năng loại người/kết thúc đã có.
- Cần kiểm chứng môi trường triển khai thực: 50–100 thiết bị, hai giờ, mất mạng/reconnect, reverse proxy/TURN, ghi âm thực tế, quota/độ trễ nhà cung cấp AI và khôi phục sau sự cố máy chủ.
- Không triển khai lên website hiện dùng hoặc sửa database production trong đợt này. Báo cáo kiểm chứng nằm tại `output/audit24/STATUS.md` của workspace, với trạng thái và bằng chứng của từng F.
