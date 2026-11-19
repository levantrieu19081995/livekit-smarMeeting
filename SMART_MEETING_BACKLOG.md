# CUSC Smart Meeting — Backlog chức năng

> File theo dõi các chức năng còn tồn đọng trong quá trình phát triển Smart Meeting.
>
> Quy ước:
> - [ ] Chưa làm
> - [~] Đang làm
> - [x] Hoàn thành
> - [!] Có vấn đề / cần xử lý lại

---

## 1. Tối ưu ghi âm & STT

**Trạng thái:** [ ] Chưa làm

### Mục tiêu
- Chỉ ghi/xử lý các đoạn có người thực sự nói.
- Tránh cuộc họp 1–2 giờ có nhiều người tham gia nhưng phần lớn không phát biểu vẫn tạo audio/STT dài.
- Ví dụ: một người chỉ nói “Tôi đồng ý” thì chỉ xử lý đúng đoạn phát biểu đó.

### Checklist
- [ ] VAD / Speech Detection
- [ ] Xác định speech start / speech end
- [ ] Tạo speech segment
- [ ] STT theo từng speech segment
- [ ] Bỏ khoảng silence không cần thiết
- [ ] Giữ đúng participant / speaker / timestamp
- [ ] Kiểm thử cuộc họp nhiều người nhưng ít người phát biểu
- [ ] Đánh giá dung lượng audio, số request STT và thời gian xử lý trước/sau tối ưu

### Điều kiện hoàn thành
- Audio/STT chỉ phát sinh khi thực sự có speech.
- Transcript vẫn xác định đúng người nói và thời gian.
- Không làm mất các phát biểu rất ngắn như “đồng ý”, “nhất trí”, “không đồng ý”.

---

## 2. Điểm cầu

**Trạng thái:** [ ] Chưa làm

### Mục tiêu
Bổ sung loại cuộc họp có điểm cầu, trong đó một thiết bị có thể đại diện cho nhiều người cùng ngồi tại một phòng.

### Checklist
- [ ] Xác định meeting type cho Điểm cầu
- [ ] Không làm ảnh hưởng luồng meeting thông thường
- [ ] Model/API cho điểm cầu
- [ ] Danh sách điểm cầu trong cuộc họp
- [ ] Danh sách người tham gia tại từng điểm cầu
- [ ] Thiết bị đại diện điểm cầu
- [ ] Join flow cho điểm cầu
- [ ] Reconnect điểm cầu khi mất mạng
- [ ] Hiển thị điểm cầu trong phòng họp
- [ ] Ghi nhận audio của từng điểm cầu
- [ ] Gắn transcript với điểm cầu tương ứng

### Điều kiện hoàn thành
- Meeting thường hoạt động như hiện tại.
- Meeting Điểm cầu có thể có nhiều điểm cầu.
- Một điểm cầu có thể chứa nhiều người nhưng dùng chung một thiết bị/mic.

---

## 3. Quản lý người phát biểu

**Trạng thái:** [ ] Chưa làm

### Mục tiêu
Xác định chính xác ai đang phát biểu, đặc biệt khi nhiều người dùng chung một thiết bị tại Điểm cầu.

### Checklist
- [ ] Chủ tọa/thư ký chọn người đang phát biểu
- [ ] Bắt đầu speaker session
- [ ] Kết thúc speaker session
- [ ] Chuyển người phát biểu
- [ ] Ghi nhận speaker + start time + end time
- [ ] Đồng bộ speaker realtime tới các client
- [ ] Gắn speech segment/STT với speaker hiện tại
- [ ] Xử lý trường hợp chưa xác định được speaker
- [ ] Yêu cầu phát biểu / raise hand
- [ ] Duyệt / từ chối yêu cầu phát biểu
- [ ] Hàng đợi phát biểu nếu cần
- [ ] Mute / unmute theo quyền chủ tọa

### Điều kiện hoàn thành
- Transcript tại Điểm cầu ghi đúng tên người đang nói.
- Chuyển người nói không làm mất hoặc gán sai speech segment.

---

## 4. AI Result / Kết quả sau cuộc họp

**Trạng thái:** [ ] Chưa hoàn thiện

### Mục tiêu
Từ transcript sau cuộc họp tạo kết quả AI có thể được thư ký kiểm tra/chỉnh sửa trước khi chốt.

### Checklist
- [ ] AI Summary / Tóm tắt cuộc họp
- [ ] Kết luận
- [ ] Quyết nghị
- [ ] Action Items / Nhiệm vụ
- [ ] Người phụ trách
- [ ] Deadline
- [ ] Cho thư ký hiệu chỉnh
- [ ] Version history
- [ ] Chủ tọa phê duyệt / hoàn tất
- [ ] Lưu kết quả chính thức vào meeting-service
- [ ] Hiển thị trong Biên bản
- [ ] Export biên bản sau khi hoàn tất

### Luồng mục tiêu
AI Generated → Secretary Review/Edit → Chairman Approval → Final

### Điều kiện hoàn thành
- AI không tự động trở thành kết quả cuối cùng.
- Người dùng có thể kiểm tra/chỉnh sửa trước khi final.
- Kết quả cuối cùng được lưu bền vững.

---

## 5. Biểu quyết / Poll

**Trạng thái:** [~] P1–P6 đã triển khai và kiểm tra; P7 còn nghiệm thu trong phòng LiveKit.

Tiến độ kế hoạch Poll (2026-10-05):
- [x] P0 — Audit Java/FE và xác định kiến trúc
- [x] P1 — Domain, Flyway V14, repositories và kiểm thử tính toàn vẹn
- [x] P2 — Lifecycle, authorization và eligible voter snapshot
- [x] P3 — Vote, concurrency/idempotency và results
- [x] P4 — Frontend services và UI quản lý
- [x] P5 — UI người biểu quyết và restore/reconnect
- [x] P6 — Đồng bộ, kết quả đóng và lịch sử
- [~] P7 — End-meeting integration, PostgreSQL/HTTP/race tests đạt; còn kiểm tra room toolbar/media/fullscreen/mobile

79 kiểm thử Poll mới đạt; Java full suite 528 bài còn đúng 2 lỗi thông báo cũ, 0 errors/0 skipped. Frontend 97 bài đạt, TypeScript/build đạt; FastAPI 210 bài đạt. Giao diện quản lý đã kiểm tra với dữ liệu và room riêng. Chưa migrate DB ứng dụng hoặc deploy. Nghiệm thu trong room đang chờ lựa chọn micro. Bằng chứng: [POLL_IMPLEMENTATION.md](meeting-service/docs/POLL_IMPLEMENTATION.md).

### Mục tiêu
Cho phép biểu quyết realtime ngay trong cuộc họp.

### Checklist
- [x] Tạo biểu quyết
- [x] Nội dung câu hỏi
- [x] Các phương án lựa chọn
- [x] Mở biểu quyết
- [x] Người tham gia vote
- [x] Ngăn vote sai / vote trùng theo rule
- [x] Đóng biểu quyết
- [x] Hiển thị kết quả realtime
- [x] Số phiếu
- [x] Tỷ lệ %
- [x] Quyền tạo/mở/đóng của chủ tọa/thư ký
- [x] Lưu kết quả sau cuộc họp
- [ ] Đưa kết quả biểu quyết vào timeline
- [ ] Cho phép đưa kết quả vào biên bản

### Điều kiện hoàn thành
- Kết quả realtime và kết quả lưu sau họp nhất quán.
- Quyền biểu quyết rõ ràng theo participant.

---

## 6. Q&A / Hỏi đáp

**Trạng thái:** [x] P1–P7 hoàn thành cho phạm vi hỏi đáp theo luồng Google Meet đã thống nhất.

### Mục tiêu
Cho người tham gia gửi câu hỏi, gửi ẩn danh và ủng hộ nội dung cần trao đổi; chủ tọa/thư ký duyệt, đánh dấu đã trả lời, ẩn hoặc xóa. Lưu lịch sử và xuất báo cáo theo quyền.

### Checklist
- [x] Người tham gia và khách có token kết nối hợp lệ gửi câu hỏi
- [x] Danh sách câu hỏi đồng bộ qua tín hiệu riêng và tải lại có xác thực
- [x] Gửi ẩn danh, giữ kín danh tính cả với người điều hành và báo cáo
- [x] Ủng hộ / bỏ ủng hộ, một lượt cho mỗi người và câu hỏi
- [x] Chờ duyệt / chờ trả lời / đã trả lời / ẩn / xóa
- [x] Chủ tọa/thư ký điều hành; quản trị viên và chủ cuộc họp theo chính sách hiện có
- [x] Bật/tắt hỏi đáp, gửi ẩn danh và chế độ duyệt
- [x] Sắp xếp, lọc, phân trang; giao diện điện thoại/tablet/desktop
- [x] Xử lý mất phản hồi, gửi lại không trùng, xung đột phiên bản và thu hồi quyền
- [x] Lịch sử chỉ đọc sau họp; báo cáo CSV cho người có quyền điều hành
- [x] P1–P7 tự kiểm tra đạt; bằng chứng tại meeting-service/target/qna-verification/REPORT.md

### Phần mở rộng chưa thuộc đợt triển khai này
- [ ] Trạng thái trung gian đang trả lời
- [ ] Gắn câu hỏi với timeline
- [ ] Lưu câu trả lời bằng văn bản
- [ ] Cho phép đưa Q&A vào biên bản
- [ ] Gửi báo cáo qua email

### Điều kiện hoàn thành
- Q&A hoạt động realtime trong meeting.
- Sau meeting, thành viên đăng nhập vẫn xem được lịch sử câu hỏi và dấu đã trả lời.
- Khách chưa có thông tin đăng nhập chỉ xem khi token kết nối còn hợp lệ.
- Kiểm tra DB/HTTP dùng PostgreSQL thật; kiểm tra phòng qua trình duyệt dùng SDK/HTTP mẫu. Chưa triển khai hoặc nghiệm thu media trên thiết bị thật.

---

# Thứ tự triển khai dự kiến

1. [ ] Tối ưu Recording / VAD / Speech Segment / STT
2. [ ] Điểm cầu
3. [ ] Quản lý người phát biểu
4. [ ] AI Result
5. [ ] Poll
6. [x] Q&A — phạm vi P1–P7 đã thống nhất

---

# Tiến độ tổng quan

| # | Chức năng | Trạng thái | Ghi chú |
|---|---|---|---|
| 1 | Tối ưu ghi âm & STT | ⬜ Chưa làm | Ưu tiên làm trước |
| 2 | Điểm cầu | ⬜ Chưa làm | Phụ thuộc nền tảng audio/STT |
| 3 | Quản lý người phát biểu | ⬜ Chưa làm | Quan trọng cho Điểm cầu |
| 4 | AI Result | ⬜ Chưa hoàn thiện | Làm sau khi transcript ổn định |
| 5 | Biểu quyết / Poll | 🟨 P1–P6 đã đạt; P7 còn nghiệm thu room | API/UI, PostgreSQL/HTTP/race và management UI đã kiểm tra |
| 6 | Q&A | 🟩 P1–P7 đã đạt | 696 backend, 177 frontend, build và 57 tình huống trình duyệt PASS; phần mở rộng tách riêng |

---

# Việc đang thực hiện

**Hiện tại:** Q&A P1–P7 đã hoàn thành, kiểm tra và dọn môi trường thử. Chưa cập nhật database hoặc khởi động lại dịch vụ đang chạy.

**Hạng mục tiếp theo:** Nghiệm thu trên LiveKit và thiết bị thật khi chạy bản mới; nghiệm thu media Poll P7 cũ vẫn là hạng mục riêng. Timeline/biên bản/câu trả lời văn bản/email chưa triển khai.

---

# Nhật ký cập nhật

| Ngày | Hạng mục | Thay đổi |
|---|---|---|
| 24/09/2026 | Khởi tạo | Tạo Master Backlog cho 6 nhóm chức năng còn tồn đọng |
| 05/10/2026 | Poll P2–P7 | API/vote/UI/sync/history/terminal closure đã triển khai; 79 Poll tests, 97 FE tests, 210 FastAPI tests đạt; P7 room acceptance còn chờ micro |
| 06/10/2026 | Q&A P1–P7 | API, quyền, ẩn danh, duyệt, ủng hộ, đồng bộ, lịch sử và CSV hoàn thành; 696 backend/177 frontend/57 browser PASS, build PASS; DB/HTTP thật và SDK phòng mẫu được phân biệt trong bằng chứng |

---

## Quy tắc cập nhật file

Mỗi khi hoàn thành một bước:
1. Đổi `[ ]` thành `[x]`.
2. Nếu đang làm, dùng `[~]`.
3. Nếu gặp blocker, dùng `[!]`.
4. Cập nhật bảng **Tiến độ tổng quan**.
5. Cập nhật mục **Việc đang thực hiện**.
6. Thêm một dòng vào **Nhật ký cập nhật**.
