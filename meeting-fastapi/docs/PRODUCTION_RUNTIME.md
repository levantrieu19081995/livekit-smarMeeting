# Capture/STT runtime — 2026-10-08

## Phạm vi hỗ trợ

Một tiến trình Uvicorn, một event loop, một worker sở hữu mỗi `CAPTURE_ROOT_DIR`.
OS file lock trên `.meeting-fastapi-owner.lock` từ chối worker thứ hai ngay khi
khởi động. Java có thể có nhiều instance cùng gọi Python này; Python không dùng
nhiều worker hoặc nhiều host cùng ghi một thư mục. Dùng volume bền vững do một
host quản lý, có atomic replace/fsync và OS lock đúng. Không bật `--reload` khi
đang họp production. Không xóa file lock hoặc đổi thư mục để vượt qua lock của
một tiến trình đang chạy.

```powershell
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1 --limit-concurrency 64 --timeout-keep-alive 5 --timeout-graceful-shutdown 45
```

IP bind cần phù hợp mạng nội bộ nếu Java nằm máy khác. Giới hạn 64 request nằm ở
Uvicorn; các semaphore STT nằm trong một tiến trình, không phải quota phân tán.
Chỉ cho Java/service nội bộ kết nối cổng này. Khóa internal riêng phải khớp Java;
production yêu cầu bật auth, khóa ít nhất 24 ký tự và credentials LiveKit.
Khóa thật nằm trong môi trường triển khai, không trong repository.

## Ghi âm và lifecycle

Bot dùng `auto_subscribe=False`, chỉ subscribe nguồn microphone audio. Adapter
cho `livekit==1.1.13` dùng queue hữu hạn và báo overflow thay vì âm thầm bỏ frame.
WAV mở/ghi batch/đóng ngoài event loop. Manifest có writer coalescing, atomic
replace/fsync; terminal status chỉ công bố sau khi ngắt phòng, drain task và
flush manifest. Các task khởi tạo recorder được theo dõi và hủy/await trước stop.

SDK reconnecting: giữ `RUNNING`, message nêu có thể có khoảng thiếu âm thanh.
SDK disconnected cuối cùng, lỗi recorder/manifest hoặc overflow: `FAILED`.
Không tự mở lại capture khác và không biến dữ liệu đã thiếu thành `COMPLETED`.
Stop retry dùng đúng session, giữ `FAILED` sau lỗi; GET status giữ các field
Java đang dùng (`captureSessionId`, `runtimePresent`, `sessionId`, `manifestPath`,
`completedAt`, `status`, `message`). Capture business state do Java quyết định.

Stop và connect được đồng bộ: một native connect về muộn phải được disconnect
trước terminal publication. Nếu cleanup chưa xác nhận được, session vẫn sở hữu
resource, new start bị chặn dù status đã `FAILED`. Request start/stop có deadline
nhưng task native/disk đang cleanup vẫn được theo dõi. Shutdown không hoàn tất
giữ OS lock trong process; phải kết thúc process trước khi restart. Supervisor
cần thời gian drain và giới hạn thời gian shutdown (lệnh trên dùng 45 giây).

Sau process crash, startup biến manifest `STARTING/RUNNING/STOPPING` thành
`FAILED`, lưu `CAPTURE_PROCESS_INTERRUPTED` và `endedAt`. Header WAV PCM16 theo
đúng format recorder được sửa theo số byte còn trên disk để giữ phần âm thanh
có thể dùng. Capture vẫn thất bại; đây không phải phục hồi các frame đã mất.
File thiếu/format không khớp/path ngoài root được đánh dấu không phục hồi được.
Không tự join lại một cuộc họp sau restart bằng credentials/session đã cũ.

## STT, idempotency và restart

Job JSON gốc `_stt_jobs/{processingId}.json` là dữ liệu authoritative. Sidecar
status và request index là dữ liệu dẫn xuất, được rebuild trước khi tiếp nhận
job sau restart. Lỗi ghi sidecar sau primary commit không đổi một kết quả thành
FAILED hoặc tạo processing ID thứ hai. Retry cùng requestId/body trả đúng ID cũ;
body khác trả 409. Cancellation drain disk thread trước khi ghi trạng thái khác
hoặc nhả lock, nên một write cũ không thể chạy muộn và ghi đè trạng thái mới.

QUEUED/PROCESSING có thể tiếp tục với cùng processing ID sau restart; terminal
results đọc từ disk. Kết quả chunk đã nhận được lưu riêng theo request ID,
audio checksum, session/segment/offset, provider/model/prompt fingerprint. Các
chunk đó không gọi lại provider khi aggregate job chưa hoàn tất.

Trước gọi provider, Python fsync marker `PROCESSING`. Nếu process chết hoặc
response không rõ trước khi có output bền vững, restart **không gọi trả phí lại**;
chunk báo `STT provider outcome is unknown; manual reconciliation is required`.
Nhà cung cấp transcription không có cơ chế bảo đảm exactly-once qua crash của
client. Chỉ explicit HTTP 429 được retry tự động; timeout/transport/5xx là kết
quả không rõ và không tự retry. Lỗi lưu receipt sau khi đã có text chỉ retry
lưu text đó trong RAM, không gọi provider lần nữa. Operator đối soát bên provider;
một lần chạy mới có request ID mới là quyết định có chủ ý, có thể phát sinh phí.
Không xóa receipt để tạo retry tự động.

Progress callbacks được coalesce, completion callbacks là advisory. Khi callback
hỏng hoặc queue đầy, GET status/result vẫn là nguồn đối soát terminal bền vững.
Java recovery đang polling trạng thái và lấy result qua processing ID. Không
đánh dấu job đã thành công chỉ vì gửi được callback.

## Giới hạn mặc định

| Giới hạn | Mặc định | Khi vượt giới hạn |
| --- | ---: | --- |
| HTTP body / deadline nhận body | 2 MiB / 15 giây | 413 / 408 |
| Capture session đang sở hữu resource | 20 | từ chối start mới |
| Microphone track / capture | 200 | capture FAILED |
| SDK frame queue / track | 200 frame | FAILED, không drop âm thầm |
| WAV queue / batch | 8 batch / 250 ms | FAILED nếu ghi không theo kịp |
| Capture connect / close deadline | 15 / 5 giây | FAILED/cleanup còn được theo dõi |
| Capture terminal metadata cache | 64 session | evict RAM; exact manifest trên disk |
| Diagnostic events / manifest | 10.000 | bỏ event cũ, tăng droppedEventCount |
| Job được chấp nhận / job đang xử lý | 200 / 8 | 429 hoặc QUEUED |
| Provider STT đồng thời / preprocessing | 4 / 2 | chờ semaphore chung process |
| Terminal STT metadata cache | 64 job | evict RAM; result/index vẫn trên disk |
| Callback task tối đa | 256 | coalesce/drop advisory; GET vẫn còn |
| STT request / toàn job deadline | 90 giây / 4 giờ | FAILED/partial truthfully |
| Audio upload chunk / manifest | 24.000.000 byte / 10 MiB | từ chối input quá lớn |
| Manifest count / chunks một job | 100 / 10.000 | từ chối input quá lớn |
| WAV segment / text một chunk | 8 giờ / 12.000 ký tự | từ chối quá giới hạn |

Các giới hạn đặt qua biến môi trường trong `.env.example`; Settings kiểm tra
range. VAD/chunk planning và WAV IO ngoài event loop; preprocessing có semaphore
và cooperative cancellation. Single-segment debug route materialize từng chunk
để tránh sao chép toàn bộ WAV dài sang nhiều file tạm cùng lúc. Các output WAV,
manifest, STT JSON/receipt được giữ bền vững trên disk; chính sách retention và
backup do vận hành quyết định, không tự xóa dữ liệu cuộc họp.

## Kiểm chứng và giới hạn kết luận

Tests cô lập dùng key giả, storage riêng, fake LiveKit/OpenAI; kiểm tra race,
OS ownership/process death, WAV repair, provider receipt/restart, cancellation
write ordering, shared quotas, overflow, callback outage, cache eviction, auth
và body bounds. Adapter queue có regression cho protocol SDK được pin; chạy lại
test này trước nâng SDK. API/AI Result, voice identity/offset và các fixture cũ
được kiểm tra cùng full pytest.

Không chạy provider trả phí, không kết nối production, không mô phỏng media
thật 100 người trong 2 giờ ở đợt này. Các giới hạn trên là giới hạn tài nguyên
trong code, không phải bằng chứng server đủ CPU/RAM/băng thông. Sau khi có server
chính thức, cần staging test media/disk/reconnect và quan sát mức queue/failure,
tốc độ ghi, resource và chất lượng âm thanh với cấu hình triển khai thật.
