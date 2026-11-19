# Cuộc họp điểm cầu và lịch sử phát biểu

## Phạm vi

`MeetingCreationMode.POINT` là cuộc họp PUBLIC, có lịch bắt đầu/kết thúc và
danh sách điểm cầu. Việc tham gia, chat, hỏi đáp, biểu quyết, tài liệu và cấp
quyền micro/camera tiếp tục dùng các luồng hiện có. Duyệt phát biểu chỉ ghi
nhận lượt nói, không điều khiển thiết bị, không tự xác định người thực sự nói,
không thay đổi kết quả transcript hoặc AI.

Người tạo quản lý metadata/danh sách người tham gia theo chính sách PUBLIC.
Riêng duyệt phát biểu: người tạo hoặc người tham gia không bị loại có vai trò
CHAIRMAN/SECRETARY do creator gán. Tài khoản nếu được gắn phải ACTIVE; khách
được gán vai trò cũng duyệt bằng participant token/connection proof hợp lệ.
Khách không được tự gán vai trò. Không có ngoại lệ cho system ADMIN.
Creator cũng được xác minh từ account của participant token hợp lệ khi web
session hết hạn; guest token không thể nhận quyền creator theo tên nhập.
Khách và các vai trò khác có thể xin và xem toàn bộ lịch sử khi có phiên vào
họp hợp lệ. Người gửi hoặc người duyệt có thể hủy yêu cầu chờ, kết thúc lượt
đang phát biểu. Việc duyệt không giới hạn số lượt nói đồng thời.

## Tạo, sửa, vào họp

- POST `/api/meetings`: `creationMode: "POINT"`, `accessType: "PUBLIC"`,
  tên/lịch như form đầy đủ; thêm `pointNames: ["Trường A", "Trường B"]`.
  POINT bắt buộc có `clientRequestId` là UUID chữ thường do client giữ cho
  cùng một lần gửi, kể cả khi mất response. Cuộc họp thường không đổi hợp đồng.
  Có 1–100 tên, mỗi tên 1–150 ký tự sau trim, không có ký tự điều khiển,
  không trùng khi chuyển về chữ thường. Roster tùy chọn, tối đa một chủ tọa
  và một thư ký như luồng tạo hiện có.
- GET `/api/meetings/{id}` thêm `points: [{id,name}]`, `pointsEditable`.
  PUT có thể gửi `pointNames`; danh sách chỉ đổi trước lần tạo connection
  đầu tiên. Sau đó chỉ cho gửi lại đúng danh sách; lịch sử/tên điểm không bị
  đổi hoặc xóa. Cuộc họp cũ không nhận pointNames khác rỗng.
- PUT POINT còn nhận tùy chọn `participants` với userPublicId/invitedEmail/
  fullName/role cho người mới, hoặc participantKey/role cho người đã có.
  Metadata, điểm và roster lưu trong cùng transaction, kiểm tra roster cuối
  tối đa một chủ tọa/thư ký; đổi chéo vai trò không cần trạng thái trung gian.
  Tối đa 500 dòng roster. Bỏ người khỏi danh sách chỉ gỡ vai trò chủ tọa/thư
  ký (về MEMBER), giữ người/connection/lịch sử trong cuộc họp PUBLIC.
  Muốn loại khỏi phòng dùng API remove hiện có. Các API participant riêng
  cũng chặn chủ tọa/thư ký thứ hai, chỉ trong POINT, dưới cùng meeting lock.
- GET `/api/meetings/{id}/join-info` thêm `creationMode` và `points`.
  POST `/join` thêm `pointId`. POINT phải chọn ID thực sự thuộc cuộc họp;
  thiếu/sai lần lượt trả 400 `MEETING_POINT_REQUIRED`/`MEETING_POINT_INVALID`.
  Cuộc họp thường không nhận pointId. Kiểm tra diễn ra trước mọi gọi LiveKit.
- Join response `participant` thêm nullable `pointId`/`pointName`. Điểm được
  lưu trên connection, không ghi đè roster: cùng tài khoản có thể dùng nhiều
  thiết bị tại nhiều điểm. Lượt phát biểu giữ snapshot điểm và tên người.

## Khôi phục khách POINT

GuestId công khai không phải chứng minh danh tính. Không có proof: server
tạo actor khách mới dù caller gửi lại guestId cũ. Để khôi phục actor, frontend
giữ guestId và chatWriteToken vừa cấp trong sessionStorage phạm vi cuộc họp,
gửi `guestResumeToken` trong lần join POINT kế tiếp. Token được băm và phải
khớp connection cũ cùng cuộc họp/guestId, có điểm và không bị loại. Proof sai
trả 403 `SPEAKING_INVALID_RESUME_TOKEN`; frontend xóa proof và để người dùng
chủ động vào bằng phiên mới. Connection mới được cấp credential mới.

Không đưa proof vào URL, log, timeline hoặc dữ liệu của người khác. Luồng
khách ở các cuộc họp thường được giữ nguyên. Khôi phục actor không phục hồi
quyền của connection LEFT/REMOVED trong cuộc họp đang chạy.

## API phát biểu

Mọi endpoint ở `/api/meetings/{meetingId}/speaking`, dùng session và header
`X-Meeting-Participant-Token` tương tự Q&A. Mutation vẫn bảo vệ CSRF.

| API | Nội dung |
| --- | --- |
| GET `/context` | pointMeeting, canModerate, readOnly, canRequest, pointId, pointName, displayName, points |
| GET `/requests?page=0&size=20` | PageResponse đầy đủ, mới nhất trước; size 1–50, offset tối đa 1.000.000 |
| POST `/requests` | `{requestId: UUID, speakerName?: string}`; trả 201 và receipt |
| PATCH `/requests/{id}/approve` | `{version: number}`; WAITING → SPEAKING, chỉ người duyệt |
| PATCH `/requests/{id}/finish` | `{version: number}`; SPEAKING → FINISHED, người gửi hoặc người duyệt |
| PATCH `/requests/{id}/cancel` | `{version: number}`; WAITING → CANCELLED, người gửi hoặc người duyệt |

Tên tùy chọn tối đa 255 ký tự sau trim. Để trống lấy tên actor được server
xác minh, không lấy tên/điểm/ID người gửi từ request body. Mỗi bản ghi gồm
id, requestId, version, pointId, pointName, speakerName,
requestedByDisplayName, approvedByDisplayName, finishedByDisplayName,
status, requestedAt, approvedAt, finishedAt, isOwn, canApprove, canFinish,
canCancel. Không trả participant/connection ID, credential hay email.

Thời điểm sự kiện do server lấy theo UTC, lưu cột TIMESTAMP dưới dạng UTC,
trả JSON Instant có hậu tố `Z`. Frontend hiển thị múi giờ Việt Nam. Đây là
thời điểm thao tác, không phải thời lượng âm thanh thực tế.

## Tính nhất quán và vòng đời

- Tạo POINT khóa hàng account creator thực tế trước mọi gọi LiveKit; unique
  receipt theo creator/clientRequestId và SHA-256 input chuẩn hóa. Title,
  point names, identity/fullName roster được trim; schedule dùng chuỗi từ
  LocalDateTime; roster sắp xếp để thay thứ tự không đổi fingerprint; mô tả
  giữ nguyên nội dung được lưu. Fingerprint không gồm key, file đính kèm hay
  participantKey không có nghĩa khi tạo.
- `meeting_point_creation_receipts` lưu đúng MeetingResponse gốc, không chứa
  token vào phòng. Retry cùng key/nội dung trả receipt trước mọi gọi LiveKit;
  đổi nội dung trả 409 `MEETING_CREATION_RETRY_CONFLICT`. Nếu cuộc họp đã bị
  xóa, ledger vẫn còn với meeting_id NULL và trả 410
  `MEETING_CREATION_RECEIPT_DELETED`; không tự tạo lại. Người dùng chủ động
  bắt đầu một lần tạo mới dùng UUID mới. Input key sai trả 400
  `MEETING_CREATION_REQUEST_ID_INVALID`.
- RoomName POINT dùng hash của creator publicId và UUID nên mất response từ
  LiveKit trước khi lưu DB vẫn thử lại cùng roomName, không sinh tên phòng
  mới. LiveKit quản lý phòng theo tên qua
  [CreateRoom/getOrCreateRoom](https://github.com/livekit/livekit/blob/master/pkg/service/roommanager.go).
  File đính kèm vẫn theo upload hiện có sau creation receipt, không nằm trong
  transaction tạo họp.
- Mutations khóa hàng meeting; join, sửa danh sách điểm, remove, end/cancel
  và cleanup dùng cùng khóa. Đọc lịch sử lấy khóa để đảm bảo kết thúc lịch
  theo giờ được phản ánh trước khi trả dữ liệu.
- UUID retry duy nhất theo meeting/participant, gắn với nội dung tên đã
  nhập và điểm. Retry giống trả cùng id/thời gian; khác nội dung/điểm 409.
- Index DB chặn hai yêu cầu WAITING/SPEAKING của cùng actor, điểm, tên
  người phát biểu. Có thể gửi cho nhiều người khác nhau trên máy chung.
- Chuyển trạng thái tăng version. Stale khác trạng thái 409; retry cùng
  trạng thái không sửa thời gian/version. Quyền vẫn được kiểm tra trên retry.
- End/cancel/remove tự kết thúc SPEAKING và hủy WAITING, lưu người kết thúc
  `Hệ thống`. End và remove đồng bộ trong transaction. Cleanup mỗi 30s
  (`app.speaking.cleanup-delay-ms`) xử lý lịch đã hết giờ, có thể khôi phục
  sau restart, tối đa 100 cuộc họp/lần. Đọc cũng đóng các lượt còn mở khi hết giờ.
- Sau kết thúc chỉ xem; credential LEFT được xem lịch sử khi đã kết thúc,
  REMOVED luôn bị từ chối. Tài khoản ACTIVE được kiểm tra từ DB mỗi request,
  session/token khác tài khoản bị từ chối. Credential chỉ thuộc meeting cụ thể.
- FK tổng hợp chặn trộn meeting/point/participant/connection; deferred FK
  hỗ trợ thứ tự xóa ORM. Xóa cuộc họp cascade điểm và lịch sử, không để orphan.
- Tên gốc giữ giới hạn 150/255; khóa so sánh có độ dài riêng 300/510 để
  chuyển chữ thường Unicode không làm vượt cột DB. JSON/query sai định dạng
  ở speaking trả 400 với thông báo cố định, không lộ lỗi parser/SQL.
  JSON/path sai kiểu ở create/join/metadata (gồm clientRequestId, pointId)
  trả 400 `MEETING_INVALID_REQUEST`, không lộ thông báo parser.

## Triển khai

Phải phát hành backend và frontend cùng hợp đồng V19. Flyway V19 bổ sung
bảng/cột/check/index; không thay thế dữ liệu cuộc họp cũ. Không chạy migration
trên DB ứng dụng trong kiểm thử. Không cần đổi FastAPI/LiveKit cấu hình.

## Kiểm chứng

Kiểm thử dùng LiveKit/capture/STT giả lập và PostgreSQL tách biệt cho HTTP,
session/CSRF, DB constraints, quyền theo vai trò, credential, retry/races và
vòng đời. Không phải chứng nhận tải media 100 người hoặc thử thiết bị thực tế.

- Regression toàn bộ, gồm idempotency tạo POINT: **865/865** Java tests, không failure/error/skip,
  PostgreSQL 16.8. Evidence:
  `target/qna-verification/point-final-release-postgres-20261008-145943-5354474c/result.json`.
- Sau hai hiệu chỉnh cuối (sanitized 400 cho JSON/path meeting sai kiểu và
  quyền duyệt của guest có vai trò do creator gán), chạy lại trên source cuối:
  **84/84** (82 kiểm thử tính năng: 59 HTTP/runtime + 23 PostgreSQL; thêm 2
  kiểm thử MeetingController), không failure/error/skip. Evidence:
  `target/qna-verification/point-final-complete-postgres-20261008-151825-67a5c07f/result.json`.
- Hai lượt có source snapshot khác nhau; không cộng thành một lần chạy trên
  cùng source. Các schema/process thử nghiệm đã dọn, listener đã đóng.
- Các trường hợp gồm guest resume hợp lệ/giả, account disabled, session-token
  mismatch, quyền từng vai trò, POINT roster atomic/role swap/rollback, khóa
  danh sách điểm sau join, input Unicode/JSON sai, UUID retry, phiên bản stale,
  cạnh tranh approve/cancel/request/end, cascade delete, lịch hết giờ và xem
  lịch sử sau kết thúc, guest officer được gán/gỡ vai trò, idempotency tạo
  họp đồng thời/mất response/đổi payload/đổi session/tombstone khi xóa, retry
  cùng roomName sau response LiveKit không xác định. Speaking không gọi
  LiveKit/capture/STT. Backend package hiện tại đã build thành công.
