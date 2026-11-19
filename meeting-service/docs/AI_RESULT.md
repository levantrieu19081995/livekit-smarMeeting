# AI Result — Kết luận bằng AI

Chức năng nằm trong phần **Kết luận** của trang biên bản. Sau khi cuộc họp kết thúc
và có biên bản, người được phép hiệu chỉnh biên bản nhấn **Kết luận bằng AI** rồi
xác nhận. Các hiệu chỉnh đang có được lưu trước khi gọi AI.

Mỗi cuộc họp chỉ có **một lần tạo kết luận AI thành công**. Nếu lỗi có thể thử lại;
sau thành công vẫn được sửa thủ công nhưng không gọi AI lần nữa. Quyền dùng lại
quyền biên bản hiện có: cuộc họp tự do do người tạo quản lý; riêng tư do người tạo,
chủ tọa hoặc thư ký quản lý. Không bổ sung quy tắc quyền cho hỏi đáp/biểu quyết.

## Dữ liệu và kết quả

AI nhận thông tin cuộc họp, biên bản mới nhất, kết quả biểu quyết đã đóng, câu hỏi
và câu trả lời đã công bố, nội dung chat đã lưu và kết luận nhập tay. Câu hỏi ẩn
danh được che tên; câu hỏi chờ duyệt/ẩn/xóa và biểu quyết chưa đóng không đưa vào.
Giới hạn: 140.000 ký tự context, tối đa 200 biểu quyết, 1.000 câu hỏi và 1.000 tin
nhắn. Vượt giới hạn báo lỗi, không cắt nguồn âm thầm hoặc làm mất nội dung biên bản.

Kết quả có tổng hợp, quyết định, kết quả biểu quyết, công việc/người phụ trách/hạn,
và vấn đề chưa thống nhất. Mỗi mục có tham chiếu nguồn. Người hoặc hạn chưa được
chỉ định được để trống; số phiếu được dựng từ số liệu đã lưu, không dùng số do AI
suy ra. Kết quả AI vẫn cần người quản lý xem và hiệu chỉnh như biên bản hiện tại.

Ba phần được lưu tách biệt:

- `manualContentBeforeAi`: bản chụp nội dung nhập trước lần AI thành công.
- `aiGeneratedContent`: kết quả AI gốc có cấu trúc.
- `finalContent`: kết luận hiện tại sau các hiệu chỉnh của người dùng.

AI bổ sung vào phiên bản biên bản mới, giữ nội dung nhập tay và lịch sử cũ. Nếu
người dùng khác sửa lúc AI đang chạy, nội dung mới được giữ. Phần nguồn gốc trên
giao diện hiển thị **Nội dung đã nhập** và **AI đề xuất**; không tạo khối nhập tay
rỗng. Kết luận cuối dùng các thao tác lưu/hoàn tất/xuất có sẵn. PDF vẫn yêu cầu
hoàn tất phiên bản trước khi xuất.

## Giao tiếp và xử lý nền

Frontend chỉ gọi Java qua `/meeting-service`; POST dùng phiên đăng nhập và CSRF.
Java xác nhận nguồn, ghi job bền vững rồi trả HTTP 202. Worker riêng gọi FastAPI;
FastAPI gọi OpenAI Responses Structured Outputs. Khóa OpenAI nằm phía Python.

- GET `/api/meetings/{meetingId}/conclusion`: trạng thái, quyền, nguồn gốc/kết quả.
- POST `/api/meetings/{meetingId}/conclusion/generate`: `expectedMinutesVersion`,
  `expectedStoredConclusion`, `manualContent` tùy chọn.
- Nội bộ POST `/api/ai/conclusions/generate`: `generationId`, `meetingId`, `context`.
  Header `X-Internal-Api-Key` luôn bắt buộc, kể cả khi capture đang dùng chế độ dev.

Flyway V18 tạo bảng `meeting_conclusions`. Java khóa hàng cuộc họp, dùng lease
300 giây và tối đa 5 lượt giao lại cho lỗi kết nối/bận. UUID/context giữ nguyên
trong các lượt giao lại. Bộ lịch ghi âm/STT hiện có được giữ nguyên, tách
khỏi lịch AI để lời gọi AI không chặn các tác vụ khác. Python lưu receipt nguyên tử và dùng khóa file của hệ
điều hành; gọi lại cùng UUID/context nhận cùng kết quả đã thành công. Tải lại
trình duyệt hoặc khởi động lại Java vẫn theo dõi/tiếp tục được công việc.

## Triển khai

Cần cập nhật đồng bộ backend, FastAPI và frontend; khởi động backend để chạy V18.
Nguồn và bản build đã được kiểm tra; lần thực hiện này chưa triển khai lên máy chủ.

Java giữ cấu hình `AI_SERVICE_BASE_URL` và `AI_INTERNAL_API_KEY` hiện có. Python:

```env
OPENAI_API_KEY=<khóa của môi trường>
INTERNAL_API_KEY=<cùng khóa nội bộ với Java>
CONCLUSION_MODEL=gpt-4o-mini
CONCLUSION_ROOT_DIR=./data/ai-conclusions
CONCLUSION_TIMEOUT_SECONDS=90
CONCLUSION_MAX_INPUT_CHARS=150000
CONCLUSION_MAX_OUTPUT_TOKENS=12000
```

Giữ thư mục receipt trên ổ bền vững khi khởi động lại/triển khai. Nếu chạy nhiều
instance FastAPI, dùng chung thư mục có hỗ trợ khóa file của hệ điều hành. Nếu
mất receipt sau một response bị thất lạc, không còn bảo đảm tránh gọi provider
lặp trên lượt giao lại. Khóa thành công của cuộc họp vẫn được Java lưu trong DB.

## Xác minh

- 786 kiểm tra Java đạt trên PostgreSQL 16.8 tạm, không có lỗi hoặc bỏ qua.
- 221 kiểm tra FastAPI và 228 kiểm tra frontend đạt; TypeScript/build frontend đạt.
- Giao diện thật với API giả lập: xác nhận/hủy, lưu trước AI, refresh khi xử lý,
  giữ phần nhập tay, khóa sau thành công, sửa kết luận cuối, lịch sử cũ, người chỉ
  xem, lỗi/thử lại và điện thoại 390px. Không dùng cuộc họp thật cho kiểm tra UI.
- OpenAI thật (`gpt-4o-mini`) đã trả kết quả có cấu trúc với dữ liệu mẫu; đọc lại
  receipt trả kết quả giống hệt. Không gửi dữ liệu cuộc họp thật trong smoke test.
- HTTP/session/CSRF và render/PDF được kiểm tra với dịch vụ Java thật, DB tạm và
  provider giả lập; các kiểm tra capture/STT/LiveKit cũ vẫn đạt.

Chức năng này là AI Kết luận. Nút tóm tắt nội dung chính bằng AI trong editor là
một chức năng khác, vẫn giữ trạng thái hiện có.
