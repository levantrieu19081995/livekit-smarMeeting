# meeting-fastapi

<!-- http://localhost:8000/docs#/ -->

FastAPI service ghi raw microphone audio tu LiveKit thanh WAV va `manifest.json`, xu ly STT va cung cap endpoint AI Ket luan noi bo cho Java. Cac luong ghi am va STT hien co duoc giu nguyen khi bo sung AI Ket luan.

So do:

```text
Java -> FastAPI -> LiveKit -> AudioStream -> WAV + manifest
```

## Cau truc source

```text
app/api          Endpoint health, start, stop, status, manifest, segments, download
app/core         Cau hinh, logging, exception va internal API key
app/models       Request/response contract Java va manifest models
app/services     CaptureManager, CaptureSession, token, participant identity, recorder, manifest
app/utils        Thoi gian UTC va sanitize path
tests            Unit test khong can ket noi LiveKit that
data             Thu muc output local
```

## Tao virtual environment tren Windows PowerShell

```powershell
cd D:\DuAn\Livekit\meeting-fastapi
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
```

## Cai dependency

```powershell
pip install -e ".[test]"
```

Dependency LiveKit dang khai bao: `livekit==1.1.13` cho RTC SDK va `livekit-api==1.2.0` cho access token.

## Tao .env

```powershell
Copy-Item .env.example .env
```

`LIVEKIT_API_KEY` va `LIVEKIT_API_SECRET` chi doc tu environment cua Python, khong nhan tu request. `.env` khong duoc commit.

Mặc định là `APP_ENVIRONMENT=production`: phải bật `REQUIRE_INTERNAL_API_KEY=true`,
cấu hình khóa nội bộ không mặc định dài ít nhất 24 ký tự và thông tin LiveKit.
Khóa nội bộ phải trùng `AI_INTERNAL_API_KEY` của Java; các API capture/STT yêu cầu
header `X-Internal-Api-Key`. `.env.example` cố ý để trống các khóa: bổ sung khóa
qua môi trường triển khai trước khi chạy. Không sửa hoặc ghi khóa thật vào source.

Chỉ môi trường local có thể dùng đồng thời `APP_ENVIRONMENT=development` và
`REQUIRE_INTERNAL_API_KEY=false`. Route AI kết luận vẫn luôn yêu cầu khóa.

## Chay service

```powershell
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

`--reload` dành cho phát triển. Lệnh production hỗ trợ **một worker** trên mỗi
thư mục lưu trữ capture/STT, có giới hạn số request đồng thời:

```powershell
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1 --limit-concurrency 64 --timeout-keep-alive 5 --timeout-graceful-shutdown 45
```

Bind IP nội bộ phù hợp nếu Java nằm máy khác. Chỉ Java/service nội bộ truy cập
Python; không public cổng Python ra Internet. Xem [vận hành production](docs/PRODUCTION_RUNTIME.md)
cho quy trình dừng/restart, các giới hạn và kết quả chưa xác định từ nhà cung cấp.

## Khởi động FastAPI

```powershell
cd D:\DuAn\Livekit\meeting-fastapi
.\.venv\Scripts\Activate.ps1
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

## Test

```powershell
pytest
```

## API contract

Health:

```http
GET /health
```

Start:

```powershell
curl.exe -X POST http://127.0.0.1:8000/api/ai/meetings/8/start `
  -H "Content-Type: application/json" `
  -d "{\"meetingId\":8,\"roomName\":\"meeting-xxxxxxxx\",\"livekitUrl\":\"ws://172.16.20.177:7880\",\"agentIdentity\":\"AI_MEETING_8\"}"
```

Status:

```powershell
curl.exe http://127.0.0.1:8000/api/ai/meetings/8/status
```

Stop:

```powershell
curl.exe -X POST http://127.0.0.1:8000/api/ai/meetings/8/stop `
  -H "Content-Type: application/json" `
  -d "{\"meetingId\":8,\"externalSessionId\":\"UUID\"}"
```

Manifest:

```powershell
curl.exe http://127.0.0.1:8000/api/capture/meetings/8/manifest
```

Segments:

```powershell
curl.exe http://127.0.0.1:8000/api/capture/meetings/8/segments
```

Download:

```powershell
curl.exe -L http://127.0.0.1:8000/api/capture/meetings/8/segments/{segmentId}/download --output segment.wav
```

## Noi xuat file WAV va manifest

Mac dinh:

```text
data/meetings/meeting-8/session-{sessionId}/
  participants/{attendee-folder}/segment-001_TR_xxx.wav
  manifest.json
```

API chi tra path tuong doi trong manifest, khong tra absolute Windows path. Download chi cho file nam trong `CAPTURE_ROOT_DIR`.

## Reconnect va segment

Mỗi `trackSid` microphone được ghi thành WAV riêng; bot tắt auto-subscribe và chỉ
đăng ký microphone, không tải camera/video/share screen. Mute/unmute ghi event.
Reconnect với track mới tạo segment mới. SDK đang reconnect giữ `RUNNING` kèm
thông báo có thể thiếu âm thanh; disconnect cuối cùng, lỗi ghi file hoặc tràn
buffer chuyển `FAILED`. Stop sau lỗi giữ nguyên `FAILED`, kèm manifest và thời
gian kết thúc khi cleanup hoàn tất. Java đọc status để đối soát trạng thái.

## Participant identity

Neu metadata LiveKit co `attendeeId`, service dung gia tri do lam khoa gom nguoi lau dai. Trong POC hien tai Java chua truyen attendee tu SSO/Google, nen service fallback sang `participant.identity` va ghi `identitySource = PARTICIPANT_IDENTITY_FALLBACK`. Email va displayName chi la thong tin mo ta, khong lam khoa gom.

## Gioi han POC

- Chi capture microphone audio.
- Khong ghi camera, screen share audio.
- Khong tu quyet dinh lich hop va khong tu start theo lich.
- Khong tao bien ban, transcript, summary hay goi model AI.
- Unit test khong ket noi LiveKit that; integration can server LiveKit reachable.

# ------------------------------- Luồng xử lý sau cuộc họp - Baseline cập nhật ---------------------------------

## 1. Khi cuộc họp kết thúc

```text
CUỘC HỌP KẾT THÚC
        ↓
Java gọi Capture STOP
        ↓
Python CaptureSession.stop()
        ↓
Dừng nhận audio
        ↓
Dừng SegmentRecorder
        ↓
Đóng toàn bộ file WAV
        ↓
Disconnect recorder khỏi LiveKit
        ↓
Finalize manifest.json
        ↓
capture_status = COMPLETED
        ↓
DỪNG HOÀN TOÀN TẠI ĐÂY
```

Sau bước này hệ thống **không làm tiếp**:

```text
KHÔNG STT
KHÔNG tạo Transcript
KHÔNG gọi LLM
KHÔNG sinh biên bản
KHÔNG xóa WAV
```

Kết quả cần giữ lại:

```text
meeting-123/
└── session-abc/
    ├── manifest.json
    └── participants/
        ├── user-A/
        │   └── segment-001.wav
        └── user-B/
            └── segment-001.wav
```

Đồng thời Java/DB cần biết tối thiểu:

```text
meeting_id
capture_status = COMPLETED
capture_session_id
manifest_path / thông tin truy xuất manifest
capture_completed_at
```

Mục đích là vài ngày sau hệ thống vẫn biết WAV và manifest của meeting nằm ở đâu.

---

# 2. Sau cuộc họp có thể chưa xử lý gì trong thời gian dài

Ví dụ:

```text
20/08
Cuộc họp kết thúc
        ↓
WAV + manifest hoàn chỉnh
        ↓
COMPLETED
        ↓

21/08
chưa ai xem biên bản
        ↓
KHÔNG XỬ LÝ

25/08
chưa ai xem biên bản
        ↓
KHÔNG XỬ LÝ
```

Trong thời gian này:

```text
WAV                → GIỮ
manifest.json      → GIỮ
Transcript         → CHƯA CÓ
Biên bản           → CHƯA CÓ
Chi phí STT        → 0
Chi phí LLM        → 0
```

---

# 3. Thư ký hoặc Chủ tọa vào Biên bản lần đầu

Ví dụ ngày 25/08:

```text
Thư ký / Chủ tọa
        ↓
Mở trang Biên bản meeting 123
        ↓
Backend kiểm tra quyền
        ↓
Kiểm tra trạng thái
```

Nếu:

```text
capture_status = COMPLETED
minutes_status = NOT_GENERATED
```

thì đây là **lần đầu cần xử lý cuộc họp**.

Không nên để request HTTP đứng chờ toàn bộ STT + LLM.

Backend tạo:

```text
Meeting Processing Job
status = PENDING
```

sau đó trả FE:

```text
PROCESSING
```

FE hiển thị:

```text
"Đang xử lý nội dung cuộc họp..."
```

---

# 4. Bắt đầu xử lý WAV khi có người cần biên bản lần đầu

Worker nhận job:

```text
PENDING
    ↓
TRANSCRIBING
```

Đọc:

```text
manifest.json
```

để biết:

```text
meeting nào

participant nào

WAV nào thuộc participant nào

segment bắt đầu lúc nào

segment kết thúc lúc nào

đường dẫn WAV

trackSid
```

Ví dụ:

```text
Nguyễn Văn A
├── segment-001.wav
└── segment-002.wav

Trần Văn B
└── segment-001.wav
```

---

# 5. STT từng WAV

Từng WAV được gửi qua Speech-to-Text:

```text
A / segment-001.wav
        ↓
STT
        ↓
text + timestamp
```

```text
B / segment-001.wav
        ↓
STT
        ↓
text + timestamp
```

Kết quả cần giữ tối thiểu:

```json
{
  "participantId": "user-A",
  "speakerName": "Nguyễn Văn A",
  "sourceSegmentId": "segment-001",
  "segmentStartedAt": "2026-08-25T09:00:05",
  "utterances": [
    {
      "startOffset": 3.2,
      "endOffset": 8.4,
      "text": "Tôi đồng ý với phương án này."
    }
  ]
}
```

---

# 6. Ghép transcript theo timeline

Dùng:

```text
segmentStartedAt + startOffset
```

để tính thời gian thực.

Ví dụ:

```text
A segment bắt đầu: 09:00:05
Câu bắt đầu offset: 3 giây

→ 09:00:08
```

Sau khi ghép tất cả participant:

```text
09:00:08 - Nguyễn Văn A
Tôi đồng ý với phương án này.

09:00:12 - Trần Văn B
Tôi sẽ phụ trách phần kỹ thuật.

09:01:05 - Nguyễn Văn A
Thời hạn hoàn thành là ngày 30.
```

Sort theo:

```text
absoluteStartTime ASC
```

---

# 7. Lưu Transcript gốc

Transcript hoàn chỉnh được lưu DB.

```text
transcript_status = READY
```

Transcript này là:

```text
DỮ LIỆU GỐC DẠNG TEXT
```

và phải được xem là:

```text
IMMUTABLE
```

LLM không được sửa đè.

Từ thời điểm này hệ thống đã có:

```text
AUDIO GỐC
WAV

+

TRANSCRIPT GỐC
Text + speaker + timeline
```

---

# 8. Sau khi Transcript READY thì tiếp tục tạo biên bản ngay

Khác với thiết kế cũ, vì chính người dùng đang chờ xem biên bản nên **không dừng ở TRANSCRIPT_READY**.

Luồng tiếp tục:

```text
TRANSCRIPT_READY
        ↓
minutes_status = GENERATING
        ↓
Lấy transcript gốc trong DB
        ↓
Lấy metadata cuộc họp
        ↓
LLM
        ↓
Sinh biên bản
```

Dữ liệu gửi LLM gồm:

```text
Tên cuộc họp
Ngày giờ
Chủ tọa
Thư ký
Danh sách người tham dự
Transcript đầy đủ
Speaker
Timeline
```

---

# 9. Sinh Minutes Version 1

LLM trả kết quả:

```text
BIÊN BẢN CUỘC HỌP

- Thông tin cuộc họp
- Thành phần tham dự
- Nội dung thảo luận
- Ý kiến chính
- Các quyết định
- Kết luận
- Công việc cần thực hiện
- Người phụ trách
- Thời hạn
```

Lưu:

```text
Version = 1
Source = AI_GENERATED
Status = DRAFT
```

Sau khi DB commit thành công:

```text
minutes_status = DRAFT
processing_status = READY
```

FE lấy kết quả và hiển thị cho:

```text
Thư ký / Chủ tọa
```

review.

---

# 10. Chỉnh sửa biên bản

Không sửa đè Version 1.

```text
Version 1
AI_GENERATED
        ↓
Thư ký chỉnh
        ↓
Version 2
SECRETARY_EDITED
        ↓
Chủ tọa chỉnh
        ↓
Version 3
CHAIRMAN_EDITED
        ↓
FINAL
```

Lần sau mở biên bản:

```text
currentVersion
```

được trả về ngay.

Không:

```text
STT lại
LLM lại
đọc WAV lại
```

---

# 11. Chống hai người xử lý lần đầu cùng lúc

Ví dụ:

```text
10:00:00 Thư ký mở biên bản
10:00:01 Chủ tọa cũng mở
```

Không được tạo:

```text
Processing Job A
Processing Job B
```

Người đầu tiên:

```text
NOT_GENERATED
        ↓
PROCESSING
        ↓
tạo Job #123
```

Người thứ hai thấy:

```text
PROCESSING
```

thì chỉ chờ Job #123.

Chỉ được có:

```text
1 processing job đang chạy / meeting
```

---

# 12. Chính sách WAV

WAV được giữ từ lúc cuộc họp kết thúc.

```text
Meeting End
        ↓
Finalize WAV
        ↓
GIỮ WAV
```

Chưa ai mở biên bản:

```text
GIỮ WAV
```

Người có quyền mở biên bản lần đầu:

```text
WAV
 ↓
STT
 ↓
Transcript
 ↓
LLM
 ↓
Minutes Version 1
 ↓
Lưu DB thành công
```

Lúc này mới bắt đầu:

```text
audio_delete_after =
minutes_generated_at + 7 ngày
```

---

# 13. Trong 7 ngày sau khi sinh Version 1

Vẫn giữ:

```text
WAV
manifest.json
Transcript gốc
Biên bản
```

Mục đích:

```text
Có thể đối chiếu audio

Có thể chạy lại STT nếu phát hiện sai

Có thể kiểm tra tên người

Có thể kiểm tra số tiền

Có thể kiểm tra ngày tháng

Có thể kiểm tra quyết định quan trọng
```

---

# 14. Sau 7 ngày

Cleanup Job kiểm tra:

```text
capture_status = COMPLETED

transcript_status = READY

transcript tồn tại DB

minutes Version 1 tồn tại

minutes_status = DRAFT hoặc FINAL

processing_status = READY

audio_delete_after <= NOW()
```

Nếu tất cả đúng:

```text
DELETE WAV
```

Sau đó:

```text
audio_status = DELETED
audio_deleted_at = NOW()
```

Có thể giữ:

```text
manifest.json
```

vì dung lượng rất nhỏ.

---

# 15. Sau khi WAV bị xóa

Hệ thống vẫn còn:

```text
Meeting metadata       → GIỮ

Transcript gốc         → GIỮ

Transcript timeline    → GIỮ

Minutes Version 1      → GIỮ

Minutes Version 2+     → GIỮ

Minutes FINAL          → GIỮ

Manifest metadata      → GIỮ

WAV                    → ĐÃ XÓA
```

Người dùng bấm:

```text
Xem nội dung cuộc họp
```

thì lấy:

```text
MEETING_TRANSCRIPT
```

trong DB.

Không cần WAV.

---

# 16. Luồng cuối cùng đã thống nhất

```text
==============================
CUỘC HỌP ĐANG DIỄN RA
==============================

LiveKit
   ↓
Python Capture
   ↓
WAV từng participant
   ↓
manifest.json


==============================
CUỘC HỌP KẾT THÚC
==============================

Capture STOP
   ↓
Đóng WAV
   ↓
Finalize manifest
   ↓
capture_status = COMPLETED
   ↓
DỪNG


==============================
CHỜ
==============================

Chưa ai xem biên bản
   ↓
Không STT
Không LLM
Không Transcript
Không Minutes
Giữ WAV


==============================
THƯ KÝ / CHỦ TỌA
MỞ BIÊN BẢN LẦN ĐẦU
==============================

Kiểm tra quyền
   ↓
Kiểm tra NOT_GENERATED
   ↓
Tạo Processing Job
   ↓
Đọc manifest
   ↓
Lấy WAV
   ↓
STT từng WAV
   ↓
Transcript từng segment
   ↓
Ghép timeline
   ↓
Lưu Transcript gốc DB
   ↓
TRANSCRIPT_READY
   ↓
LLM
   ↓
Minutes Version 1
   ↓
Lưu DB
   ↓
DRAFT
   ↓
Hiển thị cho review


==============================
SAU KHI BIÊN BẢN ĐÃ CÓ
==============================

Version 1
   ↓
Bắt đầu retention 7 ngày
   ↓
Review / chỉnh sửa
   ↓
Version 2, 3...
   ↓
FINAL


==============================
SAU 7 NGÀY
==============================

Cleanup Job
   ↓
Kiểm tra an toàn
   ↓
Xóa WAV
   ↓
audio_status = DELETED


==============================
LẦN SAU MỞ BIÊN BẢN
==============================

GET Minutes
   ↓
Lấy currentVersion
   ↓
Hiển thị ngay

Không STT
Không LLM
Không cần WAV
```

## 17. Nguyên tắc kiến trúc

Capture Service vẫn chỉ chịu trách nhiệm:

```text
LiveKit
 ↓
WAV
 ↓
manifest
 ↓
Finalize
```

STT và LLM **không được nhét vào `CaptureSession`**.

Processing biên bản là một luồng riêng chỉ được kích hoạt khi người có quyền yêu cầu biên bản lần đầu.

Đây là baseline mới để tiếp tục code.


## AI Result / Kết luận bằng AI

Java gọi POST `/api/ai/conclusions/generate` với `X-Internal-Api-Key` khớp
`INTERNAL_API_KEY`. Route này luôn xác thực khóa nội bộ, kể cả khi
`REQUIRE_INTERNAL_API_KEY=false` để dùng capture ở môi trường dev.

Cấu hình `OPENAI_API_KEY`, `CONCLUSION_MODEL` (mặc định `gpt-4o-mini`),
`CONCLUSION_ROOT_DIR` (mặc định `./data/ai-conclusions`),
`CONCLUSION_TIMEOUT_SECONDS=90`, `CONCLUSION_MAX_INPUT_CHARS=150000`,
`CONCLUSION_MAX_OUTPUT_TOKENS=12000`. Không đưa khóa OpenAI vào frontend.

Request gồm `generationId` UUID, `meetingId` và `context` chứa meeting, minutes,
polls, qa, opinions, manual. Response giữ `generationId` và `result` có summary,
decisions, pollConclusions, actionItems, unresolvedIssues. Nguồn không hợp lệ hoặc
kết quả chưa hoàn chỉnh báo lỗi; số phiếu được dựng từ dữ liệu nguồn.

Receipt được lưu bền vững bằng ghi nguyên tử và khóa file. Giữ thư mục receipt
qua các lần khởi động/triển khai; nhiều instance cần dùng chung ổ có hỗ trợ khóa
file. Java quản lý giới hạn một lần AI thành công và lưu kết luận/phiên bản DB.

Kiểm tra: 221 pytest tests đạt; OpenAI thật với dữ liệu mẫu cũng đạt và trả lại
cùng kết quả khi đọc receipt. Hướng dẫn đầy đủ: `meeting-service/docs/AI_RESULT.md`.
