# CUSC SMART MEETING — UI/UX DESIGN SPEC

**Version:** 1.0  
**Scope:** `meeting-frontend` only  
**Purpose:** Đây là tài liệu chuẩn giao diện dùng chung cho ChatGPT và CodeX khi redesign CUSC Smart Meeting.  
**Rule:** Khi source thật và tài liệu này khác nhau về business flow, **source thật + frozen business baseline thắng**. Khi nói về presentation/UI, tài liệu này là **design source of truth**.

---

## 1. MỤC TIÊU THIẾT KẾ

CUSC Smart Meeting phải có cảm giác:

- hiện đại;
- premium enterprise;
- tối giản nhưng giàu thông tin;
- giống một **modern workspace/productivity platform**, không giống admin template cổ điển;
- thống nhất với landing page hiện tại;
- nhanh, nhẹ, dễ quét thông tin;
- ưu tiên hiệu năng, khả năng đọc và thao tác;
- desktop-first nhưng responsive tốt.

Không được làm giao diện theo kiểu:

- sidebar trái cổ điển cố định;
- dashboard template chung chung;
- quá nhiều card viền dày;
- quá nhiều nút đen;
- gradient/glassmorphism nặng;
- animation dư;
- khoảng trắng vô nghĩa;
- mỗi trang một style khác nhau.

---

# 2. DESIGN DIRECTION CHỐT

## 2.1 App shell

**Bỏ sidebar trái cổ điển.**

Toàn bộ khu vực `/management/*` dùng:

```text
Top Navigation
↓
Page / Hero Header
↓
Toolbar / Tabs / Filters
↓
Main Content
```

### Top navigation

Header ngang, cao khoảng `68–72px`.

Bên trái:

```text
CUSC Smart Meeting
```

Menu giữa:

```text
Tổng quan
Cuộc họp
Biên bản
Người dùng
Cài đặt
```

Bên phải:

```text
Search
Notification (nếu source có)
Avatar + username + role
Account dropdown
```

### Navigation active state

- nền `Primary Soft`;
- text/icon `Primary`;
- bo góc `10–12px`;
- không dùng nền đen cho menu active.

---

# 3. DESIGN TOKENS

## 3.1 Colors

```text
Primary             #2563EB
Primary Strong      #1D4ED8
Primary Alt         #382BF6
Primary Soft        #EFF6FF

Heading             #0F172A
Body                #334155
Secondary           #64748B
Muted               #94A3B8

Border              #E2E8F0
Background          #F8FAFC
Surface             #FFFFFF

Success             #22C55E
Success Soft        #ECFDF3

Warning             #F59E0B
Warning Soft        #FFF7E6

Danger              #EF4444
Danger Soft         #FEF2F2

Info Soft           #EEF6FF
```

## 3.2 Typography

Primary font:

```css
font-family:
  Inter,
  "Segoe UI",
  Arial,
  sans-serif;
```

Recommended scale:

```text
Hero/Page title       40–48px / 700
Section title         24–28px / 700
Card title            18–20px / 600
Body                  15–16px / 400
Body strong           15–16px / 600
Caption               13–14px / 400–500
Button                14–16px / 600
```

Text must remain highly readable.

Do not use ultra-light font weights.

---

# 4. SPACING / RADIUS / SHADOW

Use an 8px-based rhythm.

```text
4
8
12
16
20
24
32
40
48
64
```

Recommended:

```text
Page max-width      1440px
Page horizontal pad 24–32px
Section gap         20–24px
Card padding        20–24px
```

Border radius:

```text
Small control       8–10px
Button/input        10–12px
Card                16–20px
Hero panel          20–24px
```

Shadow:

- very soft only;
- use border + background hierarchy first;
- no heavy Material-style shadows.

Example:

```css
box-shadow:
  0 8px 30px rgba(15, 23, 42, 0.05);
```

---

# 5. BANNER / HERO IMAGE

User will provide **one banner image in `public/`**.

Recommended canonical path:

```text
/public/management-banner.png
```

If actual filename differs, use the real filename. Do not rename without request.

Use the banner as a reusable visual layer for:

- `/management`
- `/management/meetings`
- `/management/meetings/create`
- `/management/minutes`

Do not duplicate the image into multiple files.

## Hero behavior

Hero is a wide card, not full-screen.

Recommended:

```text
height: 220–280px
border-radius: 20–24px
overflow: hidden
```

Image:

```css
background-size: cover;
background-position: center;
```

Use a light/blue overlay so text remains readable.

Do not darken excessively.

Hero content left-aligned:

```text
breadcrumb / eyebrow
large page title
1–2 line subtitle
primary CTA
secondary CTA (when relevant)
```

Do not place excessive content inside hero.

---

# 6. GLOBAL COMPONENT LANGUAGE

Reuse shared primitives whenever possible.

Suggested UI primitives:

```text
AppTopbar
TopNavItem
PageHero
PageHeader
SectionCard
StatCard
StatusBadge
FilterBar
SearchInput
SegmentedControl
DataTable
MeetingListItem
ParticipantAvatarGroup
ParticipantList
DocumentList
EmptyState
ActionMenu
StickyActionBar
RightInfoPanel
Modal
Tabs
```

Do not create duplicate components that do the same job.

Do not abstract prematurely if one component is used only once.

---

# 7. BUTTON RULES

## Primary

Use blue:

```text
background: #2563EB
hover:      #1D4ED8
text:       white
```

Primary CTA examples:

```text
Tạo cuộc họp
Vào họp
Lưu thay đổi
Hoàn tất
Xuất biên bản
```

## Secondary

White surface + border:

```text
background: white
border: #E2E8F0
text: #0F172A
```

Examples:

```text
Xem chi tiết
Hủy
Lịch sử phiên bản
```

## Destructive

Only for destructive action:

```text
#EF4444
```

Do not use black as default primary CTA across the app.

---

# 8. INPUT / FORM STYLE

All controls:

```text
height: 44–48px
border: 1px solid #E2E8F0
border-radius: 10–12px
background: white
```

Focus:

```text
border-color: #2563EB
box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.10)
```

Labels:

```text
14px
font-weight: 600
color: #334155
```

Validation:

- do not shift layout excessively;
- show concise inline error;
- do not use alert red background unless necessary.

---

# 9. STATUS BADGES

Use consistent semantic colors.

```text
UPCOMING / Sắp diễn ra
→ Blue

IN_PROGRESS / Đang diễn ra
→ Green

COMPLETED / Đã kết thúc
→ Neutral gray or green-complete

CANCELLED / Đã hủy
→ Red / muted red

DRAFT / Bản nháp
→ Purple / light indigo

PROCESSING / Đang xử lý
→ Amber / blue depending current source semantics
```

Status wording must come from current source/business contract.

Do not create new statuses just for UI.

---

# 10. ROUTE SPEC — `/management`

## Goal

A premium workspace overview.

## Layout

### Hero

Left:

```text
CHÀO MỪNG TRỞ LẠI

Quản lý cuộc họp dễ dàng hơn

Tạo, theo dõi và quản lý tất cả cuộc họp trực tuyến của CUSC
trên một nền tảng duy nhất, an toàn và hiệu quả.

[+ Tạo cuộc họp mới] [Xem lịch cuộc họp]
```

Right:

banner image.

### KPI row

4 stat cards:

```text
Tổng số cuộc họp
Cuộc họp sắp diễn ra
Đang diễn ra
Đã kết thúc
```

Each:

```text
icon
label
large number
optional delta / helper text
```

Do not fake delta if source does not provide it.

### Main content

3-column desktop composition:

```text
Upcoming meetings
Recent meetings
Today / quick schedule
```

If schedule data does not exist, do not invent backend data. Use existing data or omit that block.

### Quick actions

Horizontal soft cards:

```text
Tạo cuộc họp
Quản lý biên bản
Quản lý người dùng
Cài đặt hệ thống
```

---

# 11. ROUTE SPEC — `/management/meetings`

## Page header / hero

```text
Danh sách cuộc họp

Quản lý, theo dõi và tham gia các cuộc họp của CUSC.
```

CTA:

```text
+ Tạo cuộc họp
```

## Toolbar

One compact toolbar:

```text
Search
Status
Room type
Date range (only if source supports)
List / calendar view (only if implemented)
```

Do not add filters that have no data support.

## Main list

Preferred presentation:

**compact modern data list / table**, not oversized cards.

Columns/areas:

```text
Cuộc họp
Thời gian
Người tham gia
Loại phòng
Trạng thái
Thao tác
```

Meeting row:

```text
icon
meeting name
meeting code

date
time range

avatar group
participant count

PUBLIC / PRIVATE badge

status badge

[Vào họp]
[Xem chi tiết]
[…]
```

Rules:

- no huge vertical cards;
- easy to scan;
- CTA compact;
- disabled actions follow real business rules.

---

# 12. ROUTE SPEC — MEETING DETAIL

Example route is whatever current source uses.

## Page header

```text
←
Meeting title
Status badge

Meeting ID
Date
Time
Participant count

[Vào họp]
[Chỉnh sửa]
[Sao chép liên kết]
[…]
```

Only show actions supported by current source.

## Main layout

Desktop:

```text
Left 65–70%
Right 30–35%
```

### Left

1. Meeting overview
2. Description / agenda
3. Documents
4. Recent activity if source has it

### Right sticky panel

1. Meeting info
2. Chairman / secretary
3. Room type
4. Room SID
5. Join link
6. Participant list

No fake data.

---

# 13. ROUTE SPEC — `/management/meetings/create`

This page should feel like a **meeting composer**, not a long admin form.

## Hero/header

```text
Tạo cuộc họp

Điền đầy đủ thông tin để tạo cuộc họp mới.
```

Do not make hero too tall.

## Desktop layout

```text
Left 65–70%      Right 30–35%
```

### Left

#### Card: Thông tin chung

```text
Tên cuộc họp
Loại phòng
Mô tả
Ngày họp
Giờ bắt đầu
Giờ kết thúc
```

Keep current validation and value formats.

#### Card: Tài liệu cuộc họp

- drag & drop;
- list uploaded files;
- compact file rows;
- do not add new upload API.

### Right sticky column

#### Tóm tắt cuộc họp

Shows only data already entered:

```text
Tên
Loại phòng
Thời gian
Tài liệu
```

#### Người tham gia

```text
avatar group
participant count
roles
[Chọn người tham gia]
```

Reuse existing participant modal/business behavior.

#### Kiểm tra trước khi tạo

Presentation-only validation summary:

```text
✓ đã nhập tên
✓ thời gian hợp lệ
! chưa chọn chủ tọa
...
```

Must be derived from existing form state.

Do not invent new validation/business rules.

## Sticky action bar

Bottom:

```text
[Hủy]
[Lưu nháp] only if source actually supports draft
[Tạo cuộc họp]
```

If draft does not exist in current source, do NOT implement it merely because mockup shows it.

---

# 14. ROUTE SPEC — `/management/minutes`

## Hero

```text
Biên bản cuộc họp

Lưu trữ, tra cứu và quản lý biên bản cuộc họp của CUSC.
```

Use same banner system.

## KPI cards

Use existing real counts only:

```text
Tổng cuộc họp
Chưa tạo biên bản
Đang xử lý
Đã có biên bản
```

## Toolbar

```text
Search
Status
Time filter
My role
Refresh
```

Only use filters currently supported.

## Table

Columns:

```text
Cuộc họp
Thời gian
Chủ tọa
Người tham dự
Trạng thái biên bản
Cập nhật lần cuối
Thao tác
```

Use:

- sticky table header when useful;
- avatar group;
- semantic status badges;
- compact action button + menu.

Do not change minutes state machine.

---

# 15. ROUTE SPEC — MINUTES DETAIL / VIEW

## Header

```text
←
Meeting title
Meeting status

Date
Time
Participant count
Meeting ID
```

Right:

```text
Version selector
Lịch sử phiên bản
Xuất biên bản
```

Only wire to current existing actions.

## Tabs

```text
Biên bản
Thông tin chi tiết
```

Use horizontal tabs under header.

---

# 16. MINUTES DETAIL — TAB `BIÊN BẢN`

Desktop:

```text
Document canvas 70%
Info panel 30%
```

## Document canvas

A clean white document-like card.

Suggested sections:

```text
BIÊN BẢN CUỘC HỌP

Thông tin mở đầu
Nội dung chính
Kết luận
Quyết nghị / hành động tiếp theo
```

BUT:
- render only fields that source actually has;
- do not invent “Quyết nghị” if current model has no such field.

Text hierarchy must feel like a document, not a form.

## Edit actions

If current source allows edit:

```text
Hiệu chỉnh
Lưu
Hoàn tất
```

Do not change role permissions.

## Right info panel

```text
Chủ tọa
Thư ký
Thời lượng
Số người tham dự
Trạng thái ghi âm
Phiên bản hiện tại
Cập nhật lần cuối
```

---

# 17. MINUTES DETAIL — TAB `THÔNG TIN CHI TIẾT`

## Summary grid

Use 4–5 columns desktop where space allows:

```text
Meeting ID
Tên cuộc họp
Ngày
Bắt đầu
Kết thúc
Chủ tọa
Thư ký
Loại cuộc họp
Số người tham dự
Trạng thái cuộc họp
Trạng thái biên bản
```

## Participant section

Horizontal cards or compact grid:

```text
avatar
name
role badge
email if available
```

## Documents

Compact table/list:

```text
Tên tài liệu
Loại
Kích thước
Người tải lên
Thời gian
[Xem]
[Tải xuống]
```

Do not change document APIs.

---

# 18. ROUTE SPEC — `/management/users`

No sidebar.

## Header

```text
Người dùng
Quản lý tài khoản và quyền truy cập hệ thống.
```

## Toolbar

```text
Search
Role
Status
Sort
```

## Main content

Use a modern table/list, not tall cards.

Columns:

```text
User
Email
Role
Status
Actions
```

Actions go into:

```text
[…]
```

unless one direct action is important.

Do not show both “Kích hoạt” and “Vô hiệu hóa” as equally active buttons at the same time.

---

# 19. ROUTE SPEC — `/management/settings`

Use the same shell.

Settings should be divided into clear sections/cards:

```text
Meeting controls
State / time override tools
System / environment information
Administrative utilities
```

Keep it clearly labeled as admin/developer tooling if that is current scope.

Do not expose settings that do not exist in source.

---

# 20. RESPONSIVE RULES

## Desktop >= 1200

Full top nav.

Content max width around:

```text
1320–1440px
```

## Tablet 768–1199

- top nav may collapse secondary items;
- 2-column layouts can become 1 column;
- sticky right panel becomes regular section;
- tables may horizontally scroll.

## Mobile < 768

- use compact top bar;
- menu becomes drawer/popover;
- hero simplified;
- KPI cards stacked / horizontal scroll;
- actions become full-width only when useful.

Do not build desktop-only UI.

---

# 21. PERFORMANCE RULES

This redesign must remain lightweight.

Do:

- reuse CSS modules/current styling system;
- reuse existing icon library;
- prefer CSS over JS animation;
- lazy-load heavy modal/viewer only when needed;
- avoid unnecessary client components;
- avoid re-rendering entire pages;
- debounce search only where needed;
- preserve server pagination;
- use static banner from `/public`.

Do NOT:

- add chart libraries unless real charts are required;
- add UI frameworks just for styling;
- add animation libraries;
- add huge icon packages;
- add duplicate date/time libraries;
- load banner through remote URL.

---

# 22. ACCESSIBILITY

Minimum requirements:

- semantic buttons/links;
- visible focus state;
- labels bound to form controls;
- keyboard accessible dropdowns/modals;
- Escape closes modal/popover where appropriate;
- adequate contrast;
- no interaction depending only on color;
- status text always visible with status color.

---

# 23. BUSINESS/FROZEN FLOW INVARIANTS

UI redesign must NOT change:

```text
Auth flow
Google auth
Session behavior
Join flow
PUBLIC / PRIVATE rules
Participant roles
Meeting state machine
Capture
LiveKit token minting
STT
Transcript
Minutes pipeline
Minutes versioning
Permissions
Backend endpoints
Payload schemas
Enums
Statuses
Database
```

Canonical ownership remains:

```text
Frontend = UI/API consumer
Java/PostgreSQL = business source of truth
FastAPI = Capture/STT runtime executor
LiveKit = media transport
```

UI may reorganize presentation only.

---

# 24. CODEX IMPLEMENTATION RULES

For every redesign task:

1. Read current source before editing.
2. Identify exact route/component.
3. Trace current data/actions.
4. Preserve every working handler.
5. Preserve API calls.
6. Preserve current validation.
7. Preserve role/permission checks.
8. Preserve text encoding UTF-8.
9. Apply presentation changes only.
10. Inspect actual diff before finishing.

---

# 25. CHANGE BUDGET RULE

Every CodeX prompt must declare:

```text
FILES ALLOWED TO MODIFY
FILES FORBIDDEN TO MODIFY
EXPECTED SEMANTIC CHANGE
EXPECTED DIFF SIZE
```

If an unrelated file changes:

```text
REVERT IT
```

If a page only needs CSS/layout changes:

```text
DO NOT MODIFY BUSINESS JSX LOGIC
```

If `page.tsx` only needs 2 lines changed:

```text
DO NOT REWRITE THE FILE
```

---

# 26. TEXT / ENCODING GUARD

Vietnamese text must remain valid UTF-8.

Before completing, scan touched Vietnamese source for mojibake:

```text
Ã
Ä
Æ
áº
á»
Â
```

There must be no corrupted user-facing text.

Never “normalize encoding” project-wide.

---

# 27. VISUAL REGRESSION GUARD

For each redesigned route, verify:

```text
Header
Navigation
Page title
Primary actions
Forms
Tables/lists
Status badges
Responsive behavior
Existing text
```

Existing working features must remain accessible.

Do not declare DONE based only on:

```text
tsc PASS
tests PASS
```

Visual/UI tasks also require actual runtime visual verification.

---

# 28. DESIGN SOURCE OF TRUTH ORDER

When implementing, use this priority:

```text
1. Current real source / current business behavior
2. Frozen CUSC Smart Meeting baseline
3. This UI design specification
4. Approved visual mockups/screenshots
5. Generic design assumptions
```

If #1 conflicts with #3:

- keep business behavior from #1;
- adapt only presentation.

If mockup contains a button/field that does not exist in source:

- do not invent its backend/business logic;
- either omit it or render only when existing source supports it.

---

# 29. FINAL VISUAL TARGET

Every management page should visually belong to the same product:

```text
CUSC Smart Meeting
→ modern top navigation
→ blue/white palette
→ soft enterprise cards
→ clear information hierarchy
→ compact data presentation
→ premium but restrained
→ fast and easy to use
```

The UI should feel closer to:

```text
Linear
Notion
Stripe Dashboard
Microsoft Loop
modern Atlassian
```

but retain CUSC visual identity and the existing landing-page palette.

Do not copy those products literally.

---

# 30. FINAL CODEX CHECKLIST

At the end of every UI task, CodeX must report:

```text
FILES READ:
...

FILES MODIFIED:
...

FILES CREATED:
...

FILES DELETED:
...

BUSINESS LOGIC CHANGED? NO
AUTH CHANGED? NO
JOIN FLOW CHANGED? NO
API CONTRACT CHANGED? NO
BACKEND CHANGED? NO
FASTAPI CHANGED? NO
LIVEKIT CHANGED? NO
STATUSES/ENUMS CHANGED? NO
EXISTING VALIDATION CHANGED? NO
UTF-8 / VIETNAMESE TEXT PRESERVED? YES
OUT-OF-SCOPE DIFF? NO
VISUAL RUNTIME VERIFIED? YES/NO
```

If any answer unexpectedly becomes `YES`, stop and explain before continuing.

---

# 31. SHORT INSTRUCTION TO CODEX

When this file is referenced in a CodeX task:

> Follow `CUSC_SMART_MEETING_UI_SPEC.md` as the visual source of truth. Read the current source first. Preserve all existing business behavior, handlers, API calls, validation, auth, join flow, LiveKit/STT/minutes behavior and permissions. Redesign only presentation/layout within the explicitly allowed files. Do not rewrite unrelated files. Inspect the diff and revert any out-of-scope changes before finishing.

