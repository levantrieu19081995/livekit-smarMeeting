# PROJECT_CONTEXT.md — meeting-frontend

> Project: `meeting-frontend`  
> Stack: Next.js / React / TypeScript  
> Purpose: Frontend context, current behavior, integration contracts, and implementation decisions for the LiveKit Meeting system.
>
> ## Codex maintenance rule
>
> After every task that modifies this repository, Codex MUST update this file before finishing.
>
> Update only sections affected by the task:
>
> - `Current implementation`
> - `Important files`
> - `API contracts used by FE`
> - `Known constraints`
> - `Next work`
> - `Change log`
>
> Do not rewrite unrelated sections. Do not invent backend behavior. Source code remains the authority if this document becomes stale.

---

# 1. Responsibility of this repository

`meeting-frontend` is responsible for:

- Meeting management UI.
- Authentication UI and management route guard.
- Meeting create/detail/room UI.
- LiveKit conference UI.
- Realtime chat UI.
- Persisting sent chat messages through Java backend.
- Meeting documents UI.
- Minutes list/detail UI.
- Showing Minutes processing status and progress.
- Showing final Minutes versions.
- Later: showing real transcript data returned by Java.

The frontend does NOT directly call FastAPI for the Minutes/STT pipeline.

Target communication:

```text
Frontend
   ↓
Java Spring Boot
   ↓
FastAPI Python
```

---

# 2. Current implementation

## Prerendered authentication without a full-screen loader (2026-10-09)

- Homepage sign-in links directly to `/auth?returnTo=%2Fmanagement`, allowing
  Next's normal static-route prefetch to target the final page. The legacy
  `/auth/login` and `/auth/register` redirect routes remain unchanged.
- Login/register forms read `returnTo` from the current URL only after successful
  authentication in their browser submit handlers. Removing their render-time
  `useSearchParams` dependency lets `/auth` prerender the real form markup without
  a full-page Suspense fallback. Removed its unused loading CSS; existing form
  layout, image sizing, validation, APIs, pending-activation messages and panel
  switches remain unchanged. Missing/empty `returnTo` still uses `/management`.
- Verification: TypeScript, changed-code formatting, 36 existing auth/HTTP/session
  tests and an isolated production build passed. Static auth HTML contains both
  forms and no client-render bailout/full-screen loading markup. The build has
  existing unrelated docx-preview source-map and lint warnings.
- Twelve local browser checks covered homepage navigation, wrong credentials,
  both legacy aliases, encoded return-to query, login/register success returning
  to a meeting, registration awaiting activation, default management destination,
  a cold throttled load before JavaScript execution, and phone login/register
  layouts without horizontal overflow. API responses used synthetic local
  fixtures, with CSRF intact; no real account, meeting or LiveKit connection.
- Evidence: `output/auth-navigation-20261009` at workspace root. The normal `.next`
  build and shared route guards are unchanged; generated TypeScript config files
  were restored after the isolated build. No server deployment was performed.

## Centered dark meeting error screen (2026-10-09)

- `ManagementRoomError` uses a dedicated full-viewport black page with white
  title/message, a centered content column capped at 560px, and centered,
  wrapping actions with filled dark buttons. Keyboard focus remains visible.
- Auto vertical margins center short content; page scrolling keeps long error
  messages accessible on small screens. Other room/loading/prejoin styles are
  unchanged because the new theme is scoped to `errorPage`.
- Error text, optional retry callback, home destination and all room/session
  handling remain unchanged. TypeScript no-emit and changed-file formatting
  passed; live browser/reload behavior was not exercised for this UI-only change.

## Meeting toolbar order (2026-10-09)

- Toolbar visual order is End meeting, Leave, Microphone, Camera, Screen share,
  capture/minutes recording, Chat, Poll, Q&A, Documents. POINT speaking controls
  follow Documents; SDK playback-unlock controls remain available afterward.
- Presentation-only flex ordering in `ManagementRoom.module.css` keeps microphone
  and camera device-menu groups together. SDK components, portal mounting,
  button handlers, permission checks and responsive wrapping remain unchanged.
- Verification: TypeScript no-emit, the two existing conference layout tests and
  stylesheet formatting passed. Independent review confirmed SDK selectors,
  portal flex items and responsive rules preserve the requested visual order.

## Responsive date row and compact creation actions (2026-10-07)

- Creation form keeps one column for sections. Date/start/end fields use
  `dateTimeRow`: three equal columns above 767px and one column on mobile.
  Native input handlers, values, refs, errors and validation remain unchanged.
- Removed the creation intro copy/card and the duplicate bottom action group.
  Existing top Hủy/Lưu actions remain right aligned, with one submit button
  using the existing creation flow.
- `MeetingDocumentUpload` adds a presentation-only `meeting-create` variant for
  this form. Its decorative file-upload SVG is 40px and bright Google blue
  `#1a73e8`. Default/compact/edit visuals and file handlers remain unchanged.
- TypeScript, formatting and 228 existing tests passed. Source comparisons
  confirmed the form logic/edit JSX and upload logic/edit variant unchanged
  apart from formatting and an optional trailing comma in a toast call.
- Browser verification at desktop 1280px and phone 390px confirmed three columns
  versus stacked fields, one submit button, no intro/footer and a 40px blue icon.
  Phone has no horizontal overflow; required-field errors still appear.
- The top Lưu submitted one synthetic PRIVATE meeting with CHAIRMAN/SECRETARY/
  MEMBER, date/time values and one document through real frontend services
  against mock endpoints. The existing success/link popup remained. Fixture
  date/file values came through props; no production meeting was created.
  Evidence: `.next/create-form-final-layout-verification`.

## Compact single-column private creation form (2026-10-07)

- Create-only JSX removes the backend/LiveKit room metadata card and technical
  intro, and hides the locked room type selector. Existing accessType state and
  page submission logic still send PRIVATE; no request/permission change.
- Empty participant list/count/chooser summary, missing-chair placeholder and
  empty documents placeholder are omitted. Choosing participants and uploading
  documents remain available; populated lists and role/document actions remain.
  Required-field and roster validation still run on submit and show real errors.
- Scoped creationTheme CSS stacks sections and all common fields in one column.
  Existing submit actions are labeled Luu (Lưu), with busy label Đang lưu..., and
  header/footer action groups align right on desktop and phone.
- Validation: all form logic and the complete edit branch match the previous
  source after whitespace normalization; preexisting CSS declarations unchanged.
  TypeScript, formatting and 228 existing tests passed. Browser verified empty
  and populated states, required errors, file removal, a CSRF-protected PRIVATE
  sample creation with chairman/secretary/member and uploaded synthetic document,
  and existing success/link popup. Fixture dates were preset through props.
- Desktop 1280px and phone 390px each computed one grid column, no horizontal
  overflow and a right-aligned footer Save button. No production meeting created.
  Evidence: .next/create-form-single-column-verification.

Change log — 2026-10-07: Apply the five requested private-create presentation
changes while preserving create/validation/upload logic and the edit branch.

## Google-style meeting creation form (2026-10-07)

- MeetingForm's create branch gains creationTheme; existing field/button/card
  classes stay in place. The participant picker accepts an optional presentation
  className, passed only by this create branch onto its existing portal overlay.
- Scoped MeetingPages CSS adds filled #e9eef6 inputs/selects/secondary actions,
  Google blue #0b57d0 primary actions, borderless buttons and lightly tinted
  participant cards/role badges. Native controls use light color-scheme;
  keyboard focus and validation error indicators remain visible.
- No handler, state, validation, request, permission, field, layout or upload
  behavior was modified. Normalized source comparison recovers the exact previous
  components when removing the presentation additions, and all preceding shared
  CSS rules are unchanged. Edit pages and their participant dialogs do not receive
  the new theme.
- Validation: 228 existing frontend tests, TypeScript and formatting PASS.
  Browser verified mandatory-field errors, invalid end-time rejection, participant
  selection/role changes, local document selection, CSRF-protected creation via
  mock API and the existing success/link popup. The valid fixture submitted once,
  with CHAIRMAN/SECRETARY/MEMBER, unchanged datetime fields and the attached file.
  Preset fixture datetimes were used for submit/time-validation checks because
  native date/time values were not set reliably by the browser automation fill API.
- All visible create-form buttons computed 0px borders. Desktop 1280px and phone
  390px have no horizontal overflow. The edit branch retained its existing white
  controls and had no creation theme class. No production meeting was created.
- Evidence/screenshots: .next/create-form-google-verification, including
  scope-verification.json, submission-verification.json and create-form-desktop.png.

Change log — 2026-10-07: Restyle only the create-meeting form and its participant
picker to Google-style filled, borderless controls while retaining all behavior.

## AI Result / conclusion in minutes (2026-10-07)

- Latest minutes VIEW/EDIT now includes MinutesAiConclusion, calling only Java
  GET conclusion and POST conclusion/generate through the existing credential/CSRF
  proxy. The frontend never calls FastAPI or stores an OpenAI key.
- Existing minutes edit permission is reused. Java flags gate the action and
  completed/latest-minutes readiness. Confirmation explains sources, preserves
  manual input and permits only one successful use per meeting; failures can retry.
- EDIT saves dirty main/conclusion content through existing version creation before
  submitting the saved version/manual baseline. Submission prevents duplicate clicks;
  GENERATING disables editing until result/failure, recursively polls without overlap,
  and can resume after page refresh. A lost POST response or completion during
  initial loading still refreshes the latest generated minutes. Server locks remain authoritative.
- Success reloads latest minutes; normal human edit/history/finalize/export actions
  remain. GENERATED stays disabled after editing. Original manual content and AI
  structured result are shown in an optional provenance disclosure; empty manual
  input has no blank panel. AI strings render as escaped text with source labels.
- The confirmation uses a body portal to avoid inherited minutes-section CSS,
  accessible dialog labeling, focus return/trap and Escape/cancel behavior. Reader
  text does not claim editing rights. New CSS is scoped to this component.
- Validation: 228 frontend tests and TypeScript passed. Actual components with mock
  API exercised autosave-before-AI, confirm/cancel, active-job refresh, completion,
  one-use lock, later manual edit, old versions, read-only user, failure/retry and
  390px layout/blank input. Java 786/FastAPI 221 tests and real OpenAI synthetic
  smoke passed. Production build output: .next-ai-conclusion-verification.
- Evidence: .next/ai-conclusion-verification. Deployment requires frontend,
  Java V18 and FastAPI route together; no production deployment was performed.
  Full configuration: meeting-service/docs/AI_RESULT.md.
- The separate main-content AI summary button retains its existing disabled state;
  this task implements the agreed AI Result conclusion only.

Change log — 2026-10-07: Connect one-use AI conclusion to minutes editing/viewing,
preserve human input/provenance and existing version/export behavior.

## Full-width black connected room and compact header (2026-10-07)

- Connected meeting page/shell now reach both viewport edges: no outer padding,
  gap, shell border/radius or white background. Page and room wrapper background
  is #000000; connected room's LiveKit --lk-bg and fullscreen background match.
- Existing flex height allocation, responsive side-panel widths/phone overlays
  and short landscape scrolling are retained. Compact header leaves more space
  for the room. Title is white (24–30px desktop, 20px phone); room/brand label is
  #c5d2e8. The existing CUSC brand link still targets homepage.
- ManagementRoomHeader removes the home button, token/service implementation
  sentence and email/guest label. It shows only the participant name, with full
  name in the title tooltip. Removed unused span selectors from its styling.
- Dark/full-width changes are scoped to connectedPage. Prejoin background/card,
  guest/login/error/loading layouts and Google-style feature panels are retained;
  the shared header text simplification also applies to prejoin.
- Product changes are limited to ManagementRoomHeader.tsx and
  styles/management/ManagementRoom.module.css; no media, toolbar, meeting/auth,
  Q&A, poll or API business logic was modified.
- Validation: TypeScript, 222 existing frontend tests and targeted formatting
  passed. Browser checks used the real header, CSS, LiveKit VideoConference with
  an offline fixture room, and real side panels with mocked HTTP. Covered default
  desktop, nominal 390px phone, 768px tablet, 844x390 landscape, long names/titles,
  guests, Q&A/poll open/close, and prejoin styling. No horizontal overflow.
- Evidence: `.next/room-dark-verification/REPORT.md` and screenshots. No new
  production build, live media test or public deployment was performed.

Change log — 2026-10-07: Make the connected room edge-to-edge on a pure black
background and simplify the header with brighter meeting titles.

## Written Q&A answers (2026-10-07)

This supersedes earlier notes that ANSWER only marks a question as answered.

- The question action is now “Trả lời”, opening an inline `QnaAnswerForm`.
  A nonblank answer up to 5000 characters is required by the new UI. Cancel,
  character count, disabled/pending state and Google-style controls are included.
- Responses show answer text, responder snapshot and answeredAt beneath the
  question; content remains plain text with preserved newlines. Existing
  status-only ANSWERED questions show “Thêm câu trả lời”; saved answers can be
  edited while the meeting is live. Existing canManage permissions are retained
  (including members/guests under the open participation contract).
- `POST .../qa/questions/{id}/actions` now accepts optional `answer` with
  action ANSWER and expectedVersion. Responses add nullable `answer` and
  `answerAuthorDisplayName`. History and CSV retain these persisted fields.
- Saving uses the current observed version, checks the confirmed answer text
  before showing success, and updates the displayed row from the server receipt.
  A failed/conflicting write keeps the draft. If the server answer changes while
  editing, submission is disabled until the user reopens/reviews it. Existing
  polling, realtime hints, token checks, CSRF and other moderation are unchanged.
- Completed/cancelled meetings retain readable answer text and hide the reply
  editor. Frontend and backend (Flyway V17) must both be updated for written saves.
- Validation: 222 frontend tests, TypeScript, formatting and production build
  passed. Real-component browser checks covered blank input, multiline save,
  legacy answer addition, editing, mobile width, conflict/old-server failure
  retention and terminal history. Backend validation: 767 tests passed on the
  isolated PostgreSQL run. No public deployment was performed.
- Evidence: `.next/qna-answer-verification/REPORT.md` and screenshots. Key files:
  QnaAnswerForm, QnaQuestionList, MeetingQnaPanel, managementQnaService and qna types.

Change log — 2026-10-07: Added persisted written answers to the Q&A workflow.

## Google-style Q&A and poll panels (2026-10-07)

- Q&A, its settings/delete overlays, poll list/editor/ballot/results and history
  now share the light palette and control base in
  `styles/management/MeetingSidePanel.module.css`, composed by Qna/Polls modules.
- White surfaces, gray-lavender filled fields/secondary controls (`#e9ecf5`),
  dark text (`#202124`) and Google blue primary actions (`#0b57d0`). No control
  borders; focus outlines remain visible. Selected controls are pale blue,
  disabled buttons neutral gray, and destructive actions pale red.
- Q&A controls no longer inherit dark LiveKit control variables. Native select
  menus use the light color scheme. Checkboxes/radios use the primary blue.
- Poll close control now uses a 16px X icon and a 44px target, matching Q&A.
  Button hit targets remain at least 44px. Existing phone/tablet field sizing,
  poll history card layout and label/value alignment are retained.
- Backend/API, permissions, form validation, polling, retry logic and write
  payloads were not changed. Only panel CSS and the poll close icon changed.
- Validation: TypeScript and 220 existing frontend tests passed; targeted
  formatting passed. Browser checks used actual components/HTTP clients with
  mocked responses and the actual LiveKit/global styles. Covered settings save,
  anonymous moderated question submission, multi-choice draft save, delete
  cancellation/Escape, empty/populated/terminal states, desktop, nominal 320px
  phone and 768px tablet sizes. No horizontal overflow in either panel.
- Evidence and screenshots: `.next/panel-google-verification/REPORT.md`.
  No public deployment or server restart was performed.

Change log — 2026-10-07: Unified Q&A/poll panel controls and colors with the
supplied Google-style reference.

## Homepage inline creation choices (2026-10-07)

- Homepage “Tạo cuộc họp mới” now switches the card tab instead of routing to
  the generic management create page. Classic and Immersive layouts both show
  the three choices immediately: INSTANT, LATER and PRIVATE.
- LATER/PRIVATE link straight to their mode-specific forms. INSTANT reuses
  `useInstantMeetingCreation` with the management create menu: same payload,
  pending duplicate guard, error handling and share dialog. The homepage dialog
  is portaled outside the story's transformed/hidden panels.
- Switching back to Join preserves the meeting ID. Switching home modes retains
  the selected task. Creation stays mounted across tab switches, retaining an
  in-flight response and its dialog.
- Authentication is preserved. Guest links retain the selected mode in
  `returnTo`; instant login returns to `/?tab=create` without creating a meeting
  automatically. Auth/create pending state blocks all three choices.
- Important files: `components/home/HomeMeetingCreateOptions.tsx`,
  `hooks/useInstantMeetingCreation.ts`, `hooks/useHomeMeetingActions.ts`,
  `styles/HomeMeetingCreateOptions.module.css`, ClassicHome and ScrollStory.
- Validation: TypeScript, targeted formatting and 220 frontend tests passed.
  Browser tests used the real homepage/menu, mocked HTTP and an omitted external
  Spline scene. Covered themes, phone layout, no tab navigation, direct mode URLs,
  one instant POST and local dialog, guest/login behavior, join-by-ID and existing
  management menu callback. Backend API/business rules were not modified.
- Evidence: `.next/homepage-create-verification/REPORT.md`. No public deployment
  or server restart was performed.

Change log — 2026-10-07: Put the three choices directly in the home create tab
and reused the instant creation flow with the management menu.

## Meeting creation modes and owner permissions (2026-10-07)

This is the current permission contract; it supersedes older meeting, minutes,
poll and Q&A role restrictions documented below.

- All signed-in accounts can create meetings. The create menu offers INSTANT,
  LATER and PRIVATE. INSTANT posts immediately, with server time and no end;
  LATER uses a title/description form with no schedule and starts on first join;
  PRIVATE keeps the complete scheduled participant/document form.
- Successful creation opens an accessible native dialog with basic information,
  the current site's `/join/{id}` link, copy, detail and enter actions. Instant
  creation refreshes the list/dashboard. Pending guards prevent duplicate writes.
- PUBLIC lists/details are visible to every signed-in account. PRIVATE visibility
  requires an active invitation or ownership. The five menu destinations remain
  visible; existing user-account/settings administration guards remain.
- Meeting and minutes DTOs provide `canManage`. PUBLIC management belongs only to
  the creator; PRIVATE management belongs to creator/chairman/secretary. Edit,
  delete, minutes actions and room capture/document controls use server flags,
  including creators who have a guest/member role. No global ADMIN bypass.
- Start/end timestamps are nullable for quick modes; list/detail/dashboard,
  settings and minutes mappers handle them without fabricating a schedule.
- Polls pass the join credential for every read/write, including guests. The
  editor no longer selects an eligible subset; every active participant can vote,
  including late arrivals. Q&A and poll management are open to participants,
  with meeting scope, removed/disabled credential rejection, CSRF, single votes,
  retry handling and completed-meeting read-only behavior preserved.
- Validation: 755 backend tests, 220 frontend tests, TypeScript and production
  build; browser UI verification uses real components/services with mocked API
  responses at desktop, phone and tablet sizes. HTTP/backend verification uses
  isolated PostgreSQL and real sessions/controllers/services, with external
  LiveKit/capture/STT controlled. Evidence: `../meeting-service/target/meeting-flow-verification/REPORT.md`.
- Deployment: source and tested backend jar are prepared. The public application
  has not been restarted or migrated by this task; deploy FE/BE together with V16.

Change log — 2026-10-07: Added three creation modes and share dialog; consolidated
owner/private-officer management; opened poll/Q&A actions to participants/guests.

## Open navigation and meeting visibility (2026-10-07)

- All authenticated system roles receive the five management menu destinations.
  USER can open the overview without being redirected to minutes. Below 1400px,
  the decorative search area yields space so the settings link is not covered;
  the existing mobile drawer includes every destination.
- Java returns all PUBLIC meetings plus PRIVATE meetings where the current
  account is its creator, a non-removed roster entry or matches an unbound email invitation.
  List counts/pagination, detail, participants, minutes, documents, capture reads,
  polls and Q&A history use the same rule. PRIVATE has no implicit admin bypass.
- PRIVATE creation and PUBLIC-to-PRIVATE conversion invite the creator as MEMBER
  if absent. Existing chairman/secretary assignments remain as entered.
- Minutes preserve a null meeting role as a viewer. List actions route viewers
  to detail; detail hides generation/retry/edit/finalize while keeping reading
  and export. Editing follows server `canManage` under the owner/private-officer contract above.
- Users/settings destinations remain visible with their administrative page/API
  guards. Q&A requires a join credential to ask/upvote; participant management,
  moderation and CSV now follow the open participant contract above.
- Validation: 220 frontend tests in 22 suites, TypeScript and targeted formatting
  passed. Browser checks covered USER overview, all five uncovered desktop links,
  phone drawer/navigation and viewer versus secretary minutes UI with the real
  components/services and controlled HTTP responses. Backend: 735 tests passed
  on isolated PostgreSQL. Evidence and backend jar:
  `../meeting-service/target/open-access-verification/REPORT.md`.

Change log — 2026-10-07: Opened menu/overview and PUBLIC reads, enforced PRIVATE
roster visibility, and matched minutes viewer presentation to the API.

## Compact Q&A controls (2026-10-07)

- History, available CSV report, settings and close share the panel heading.
  Settings uses a gear icon with the existing native disclosure form shown as a
  compact popup. Escape returns focus to the gear; an outside pointer closes it.
  Closing/reopening retains unsaved checkbox values; confirmed saves still use
  the existing settings-version remount.
- The question label and character count share a row, followed by the composer
  and a single anonymous/send action row. Sorting and filtering remain two
  adjacent selects; refresh sits beside the question count. Narrow screens wrap
  controls without horizontal overflow.
- Buttons/selects use filled, borderless LiveKit control colors within the Q&A
  CSS module. Icon controls retain 44px touch targets, accessible names and
  keyboard focus indicators. Question cards use a light filled background.
- Production files: `components/management/qna/MeetingQnaPanel.tsx`,
  `QnaSettings.tsx`, new `QnaIcon.tsx`, and `styles/management/Qna.module.css`.
  API payloads, permissions, report availability, polling and question actions
  are unchanged. No other page or room control styles were modified.
- Validation: TypeScript, targeted formatting and 41 existing Q&A tests passed.
  Browser checks with the real components/services and controlled HTTP fixtures
  covered desktop/mobile layout, settings save/draft/focus, sending, approval,
  upvoting, answered status, filters, delete cancellation and member/ended guards.
  The requested 320px viewport was reported as 317px by the in-app browser; all
  visible controls and the settings popup stayed in bounds. These are local UI
  checks, not a production backend/media or deployment verification. Evidence:
  `../meeting-service/target/qna-compact-ui-verification/REPORT.md`.

Change log — 2026-10-07: Compacted the Q&A sidebar and aligned its controls with
LiveKit styling while preserving existing question and permission behavior.

## Q&A connection error diagnostics (2026-10-06)

- The shared HTTP client preserves the optional string `ErrorResponse.code` in
  `ManagementApiError` for JSON, blob and void failures. Existing two-argument
  error constructors, response status/message, cookies, headers and CSRF remain
  compatible.
- Q&A maps six known authorization codes only on HTTP 403 to connection,
  account/session mismatch, invalid credential, disabled account or removed
  participant guidance. Other codes/statuses keep the controlled backend message.
  `QA_CONNECTION_NOT_CONNECTED` no longer implies that a secretary lacks a role;
  its wording also covers LEFT/REMOVED connections without promising recovery.
- No permission, participant token, room/join lifecycle or polling changes were
  made. Existing polling restores the composer once backend authorization accepts
  the connection, and authorization failures still clear private UI state.
- Reported room 35/account lvtrieu was checked read-only: ACTIVE/SECRETARY, two
  PENDING connections with no confirmed join. Public and localhost join metadata
  matched. The configured dev LiveKit server returned no participants during the
  latest probe; missing webhook versus incomplete media connection is unresolved
  until the affected user stays in the room during observation. This diagnostic
  patch does not claim to fix or deploy the room connection. Follow-up on
  2026-10-07 confirmed room 37's exact connection ACTIVE in LiveKit but PENDING in
  the configured DB. Backend Q&A now accepts valid join-issued PENDING sessions
  for all participant roles; see the participation update below.
- Validation: 214 frontend tests in 21 files, TypeScript and targeted formatting
  passed. Eight browser error/recovery cases passed using the real panel/services
  and mocked HTTP responses; final evidence is in
  `../meeting-service/target/qna-connection-diagnostics/REPORT.md`.

Change log — 2026-10-06: Retained backend error codes and made Q&A authorization
errors describe the actual connection/account condition; access rules unchanged.

## Q&A available before LiveKit webhook confirmation (2026-10-07)

- Java's Q&A policy now returns canAsk/canUpvote for a valid join-issued token
  whose connection is PENDING or CONNECTED. Chairman, secretary, member and guest
  have the same ability to ask; existing moderator restrictions remain.
- This removes the false 403 when a real room connection is ACTIVE in LiveKit
  but its participant-joined callback has not updated Java's DB connection.
- The existing frontend panel and services need no new role checks or token
  fallback. Its current authoritative context conditions display the composer.
  LEFT/REMOVED, invalid/cross-meeting or mismatched-account tokens remain denied.
- No frontend production TS/CSS changes for this follow-up. Real Java HTTP tests
  use the actual join endpoint, issued PENDING token and CSRF session before asking
  and upvoting; LiveKit room/token issuance is mocked. Evidence:
  `../meeting-service/target/qna-participation-verification/REPORT.md`.

Change log — 2026-10-07: Aligned Q&A availability with Java's updated join-issued
participant-session contract; no frontend role or room behavior changes.

## Create meeting participant roles (2026-10-06)

- PUBLIC and PRIVATE creation both offer CHAIRMAN, SECRETARY and MEMBER in
  the picker and participant role editor. New selections fill a missing chairman
  first, then a missing secretary, then default to MEMBER. Multi-select and later
  picker batches include existing and newly selected roles when choosing defaults.
- Creation allows at most one chairman and one secretary. A chairman remains
  required; creating without a secretary is valid. The picker rejects duplicate
  secretary assignments, including a secretary already added before reopening;
  confirmation, role editing and form submission validate the same create-only
  limit. Java's create validation also enforces the secretary cap.
- Switching PRIVATE to PUBLIC during creation retains selected members and
  their roles. The obsolete removal confirmation/filter is gone. PUBLIC create
  descriptions, counts and buttons now refer to participants and all three roles.
- Edit behavior remains unchanged: new people default to MEMBER once a chairman
  exists; existing multiple secretaries remain permitted. Unchanged joined/left
  GUEST participants retain their persisted identity, role, status and timestamps.
  Mutation payload fields and participant synchronization are unchanged.
- Important files: `lib/meetingParticipantRules.ts`,
  `components/meetings/ParticipantPickerDialog.tsx`,
  `components/meetings/MeetingForm.tsx` and their relevant rule/API tests.
- Validation: 179 frontend tests, TypeScript and targeted formatting passed.
  The full backend suite passed 719 tests on real PostgreSQL, including eight new
  unit cases and 15 HTTP cases. Independent frontend source review and the offline
  fixture bundle passed. Fifteen browser scenarios passed with the real form,
  picker and HTTP services against controlled mock responses, including phone
  layouts, creation payloads and joined-guest/edit regressions. An additional
  empty-page full-save check could not populate native date/time controls through
  the browser automation; its required-field validation blocked HTTP correctly.
  That check is recorded as incomplete, not a save PASS. Evidence:
  `../meeting-service/target/participant-create-verification/`.

Change log — 2026-10-06: Enabled MEMBER selection during meeting creation,
assigned one chairman/secretary before member defaults, enforced the create-only
secretary cap and retained selected members when switching to a public room.

## Centered pure-black meeting loading (2026-10-06)

- Both join loading branches use `loadingPage` with a solid `#000000` background
  and a status block centered horizontally/vertically across `100dvh`. Safe-area
  padding, min-content scrolling and wrapping keep small viewports usable.
- The heading is consistently `Đang chuẩn bị cuộc họp`; details distinguish loading
  metadata from confirming participation, without exposing token/backend details.
  `role="status"` announces the waiting state.
- Only loading render markup/copy and four scoped CSS rules changed. Predicates,
  requests, authentication, error handling, guest/prejoin/connected flow and shared
  error/room styles remain unchanged. No dependency or animation added.
- Verification: 177/177 frontend tests, TypeScript/format, independent source review
  and 8/8 browser cases PASS. Five viewport sizes measured black background and
  center coordinates; held metadata/auth/join responses released to the same guest,
  prejoin or error screens. All 20 unaffected baseline files stayed unchanged.
- Evidence: `meeting-service/target/meeting-loading-verification`; real route/UI
  with mock HTTP controls and synthetic device/SDK boundaries. No current services
  or database were restarted/migrated; no deployment/physical-device acceptance claimed.

## Prejoin palette aligned with guest entry (2026-10-06)

- The microphone/camera preparation screen uses the guest entry deep-black gradient
  (`#08090b`, `#030405`, `#010102`), graphite/silver cards, muted secondary text and
  black/silver buttons. Device popups, selected/hover states and warnings are themed.
- `MeetingRoomClient.tsx` adds `preJoinPage` only to its existing `!userChoices`
  branch. All new CSS in `ManagementRoom.module.css` is scoped under this modifier;
  the old CSS is unchanged. The theme is removed when the user enters the room.
- Header/prejoin components, validation, device handlers, SDK props/defaults,
  authentication, join requests and navigation remain unchanged. Guest name entry,
  private authentication and connected conference retain their existing styles.
- The preview uses 16:9 with a bounded height; mobile safe areas, short-viewport
  scrolling and reduced-motion support remain. The page has no grid or extra outer
  frame. No dependency or production mock was added.
- The requested space backdrop now adds two cropped monochrome planets, orbit
  arcs, sparse stars and three concentric elliptical floor rings on desktop.
  `ManagementPreJoinBackdrop.tsx` uses `PreJoinBackdrop.module.css` and a small
  local `public/images/prejoin-planet-texture.svg`. The static layer is pointer-inert,
  aria-hidden, clips its own overflow and stays below the header/card via page
  isolation. It unmounts on connection. Mobile uses one faint planet and two rings.
- Planet-size refinement matches guest entry: desktop left 660px/right 420px;
  left becomes 330px at max-width 600px. Crop offsets keep both side planets visible
  on desktop. Floor rings, orbits, palette, component and meeting/device logic stay unchanged.
  Format/source review and three direct browser comparisons with guest entry
  (desktop/tablet/phone) PASS. Evidence: `planet-size-ui-checks.json`,
  `planet-size-review.json` and `planet-size-*.png` in prejoin-space verification.
- The prejoin header information block has no outer border or inset white highlight.
  Its background is transparent with no shadow, blending into the deep-black page.
  Its content, user pill, home button, central prejoin card and connected-room header
  keep their existing behavior/styling. The override is scoped to `preJoinPage .header`.
  Targeted formatting, independent CSS review and 3/3 browser checks PASS;
  evidence: `header-border-ui-checks.json`, `header-border-review.json` and
  `header-no-border.png` in prejoin-space verification.
  The subsequent transparent-background refinement passed format, source review
  and 3/3 browser checks, preserving the central card and connected header.
  Evidence: `header-transparent-ui-checks.json`, `header-transparent-review.json`
  and `header-transparent.png`.
- Backdrop follow-up verification: 177 frontend tests, TypeScript/format and 6/6
  browser cases PASS, including six viewport sizes, long titles, menu click-through,
  mobile simplification and complete removal when entering the room. Existing
  source changes are limited to its import/conditional and two prejoin positioning
  declarations. Independent review found all 17 other baseline files unchanged.
  Evidence: `meeting-service/target/prejoin-space-verification`.
- Verification: 177/177 frontend tests, TypeScript and targeted formatting PASS;
  9/9 browser cases PASS, including six viewport sizes, long titles, popup palette,
  mobile menu bounds, micro warning, camera toggle, guest entry and entering the room.
- Browser checks use the installed LiveKit PreJoin with browser-generated silent
  audio/canvas video, mocked HTTP and a conference SDK shim. No physical device,
  remote LiveKit or deployment acceptance is claimed. The fixture records an
  existing SDK selection-observer limitation separately; no SDK code was changed.
- Independent source review confirms the exact previous CSS prefix and 17 unaffected
  source files are unchanged. Evidence: `meeting-service/target/prejoin-theme-verification`.

## Dark Space guest meeting entry (2026-10-06)

- The guest name step on `/join/{meetingId}` follows the supplied Dark Space v1.0
  design with requested refinements: deep black background, two monochrome cropped planets,
  centered dark card, black/silver submit button and semantic red error notice.
- `GuestJoin.module.css` owns all styling and decorative elements. The guest
  render branch no longer uses the light room page/shell wrappers. Private
  authentication, signed-in entry, device prejoin and connected room remain unchanged.
- Existing trim/minimum-two-character validation, 100-character limit,
  autofocus, API error precedence, disabled submitting state, guest identity,
  CSRF requests and navigation are preserved. ARIA error/title associations and
  busy/alert semantics improve accessibility without altering handlers.
- Mobile uses one subdued planet, 16px input text, 50px controls and safe-area
  padding. Long titles wrap; the page scrolls when the viewport is short.
  Reduced-motion preference disables entry animation and hover movement.
- Follow-up: removed the outer silver border, rounded frame and page gutters.
  The background/decorations cover the viewport edge to edge; the centered form
  retains its border and responsive safe spacing. No TS/API changes in this follow-up.
  Targeted formatting and three browser viewport checks (desktop/tablet/phone)
  PASS, confirming zero outer border/padding, full viewport coverage and no
  horizontal overflow. Evidence: `fullscreen-ui-checks.json` and updated screenshots.
- Latest visual refinement removes the grid at every breakpoint and deepens the
  background to `#08090b` / `#030405` / `#010102`. Form contrast, planetary decoration,
  responsive spacing and all TS/API logic remain unchanged.
  Formatting and desktop/phone browser checks PASS; computed decoration background
  is `none`, the deep-black gradient is applied and no horizontal overflow occurs.
  Evidence: `deep-black-ui-checks.json` and `deep-black-*.png`.
- Unused former guest-only room styles were removed; styles shared by the private
  authentication card retain their existing declarations. No dependency or raster
  asset was added.
- Verification: 177/177 existing frontend tests, TypeScript and targeted format
  checks PASS; 15/15 browser scenarios PASS, including seven requested viewport
  sizes, invalid names, pending/error/retry, returning guests, private/authenticated
  entry, keyboard submit, short-screen scrolling and reduced motion. Independent
  source review confirms 12 critical API/auth/media files remain byte-identical.
- Evidence and screenshots: `meeting-service/target/guest-entry-verification`.
  Browser fixture uses real route/auth/UI components with HTTP/SDK/hardware
  boundaries mocked; no deployed backend or physical Safari/device test is claimed.

## Q&A / Hỏi đáp (2026-10-06)

- `MeetingQnaPanel.tsx` is shared by meeting detail, the conference side panel
  and `/management/meetings/{meetingId}/qa`. The dedicated history route inherits
  the management authentication guard/shell and reads Q&A directly, so an
  authorized ordinary member does not need the generic meeting-detail permission.
  Signed room participants receive the history link; guests do not.
- Participants can ask named/anonymous questions, support or remove support,
  sort by newest/oldest/popularity, filter their questions and paginate. Java
  controls all permissions, counts and visibility. PENDING questions are visible
  only to their author and moderators; HIDDEN/DELETED records are moderator-only.
  Anonymous questions always display `Ẩn danh`, including moderator views/reports.
- Moderators can enable Q&A/anonymity/moderation, approve, mark answered, hide
  and confirm deletion. Settings/actions send `expectedVersion`; a conflict
  reloads data and asks the user to decide again. Disabling moderation retains
  existing pending questions. Marking answered records a state/time, not an answer
  text. Turning anonymity off never silently converts an anonymous draft to named.
- Ask retries reuse the same UUID for unchanged content/anonymity after an
  ambiguous response. Changed meeting/token/account mounts a fresh private
  session. Authorization failures immediately clear questions, draft, retry and
  moderator state; stale responses and unmounted callbacks are ignored.
- Visible active panels read every 3 seconds and recover on focus, online,
  visibility restoration and room reconnect. Reads coalesce around the existing
  request coordinator; hidden/unmounted/ended panels stop periodic reads. Room
  hints use `cusc.qna.changed` with only `{meetingId}` and never grant access.
  Anonymous asks suppress peer hints because LiveKit exposes the packet sender;
  polling supplies their updates. The original Poll coordinator API re-exports
  the same shared implementation without changing Poll behavior.
- Completed/cancelled meetings expose read-only history. Authorized moderators
  can download Java's UTF-8 BOM CSV in live, disabled or ended meetings when QA
  has saved questions. The client verifies permission again before download,
  preserves CSV bytes, uses a
  safe filename and cleans the object URL. There is no file download after
  unmount/identity change/permission loss.
- Opening Q&A adds a sibling side panel and keeps the existing LiveKit room,
  media initializer, chat persistence, capture and Poll/Document controls mounted.
  Q&A uses its own components/CSS/services and no new dependencies/global styles.
- Final frontend checks: 177 tests in 21 files, TypeScript, targeted formatting
  and the isolated production build passed. P3–P7 passed 57 browser scenarios
  through real production components/services with mocked network/LiveKit
  boundaries, including actual browser fullscreen and CSV downloads, room
  media/chat/capture control isolation and phone/tablet layouts. Evidence and phase
  gates: `../meeting-service/docs/QNA_IMPLEMENTATION.md` and
  `../meeting-service/target/qna-verification/`. These source checks do not claim
  deployment or a real media/capture connection.

Important files:

```text
components/management/qna/MeetingQnaPanel.tsx
components/management/qna/QnaQuestionList.tsx
components/management/qna/QnaSettings.tsx
components/management/qna/QnaDeleteDialog.tsx
components/management-room/ManagementQnaBridge.tsx
components/management-room/ManagementVideoConference.tsx
app/management/meetings/[meetingId]/qa/page.tsx
services/management/managementQnaService.ts
services/management/managementRequestCoordinator.ts
services/management/qnaRefreshScheduler.ts
services/management/qnaRoomSync.ts
services/management/qnaReportDownload.ts
services/management/qnaErrorMessage.ts
types/management/qna.ts
styles/management/Qna.module.css
```

Report visibility refinement — 2026-10-07: Empty meetings hide the CSV button.
`MeetingQnaPanel.tsx` tracks the unfiltered moderator-visible question count;
empty MINE/PENDING filters retain the report when the meeting has questions.
Filtered moderator views use one ALL row to obtain that total, while other
viewers make no extra read and ordinary lists keep the default size=20. Availability
uses the existing refresh coordinator and resets with permission/session state.
CSV format and backend permissions are unchanged. First-question, external-question,
filtered-empty, member, revoked-permission and ended-meeting cases passed in the
local browser fixture; 35 focused tests, TypeScript and formatting also passed.
Evidence: `../meeting-service/target/qna-empty-report-verification/`.

Change log — 2026-10-06: Added persisted Q&A participant/moderator UI, room
synchronization, dedicated postmeeting history and authorized CSV export.

## Homepage brand links and poll history table (2026-10-06)

- Clicking the CUSC Smart Meeting brand in the management header/drawer,
  management breadcrumbs, standalone page headings and room heading now
  targets the public homepage `/`. `components/home/HomeBrandLink.tsx`
  shares that destination and preserves each page's existing typography.
  The Overview navigation item still targets `/management`.
- `MeetingPollHistory.tsx` renders poll history as a semantic table with
  question, status, participation count, personal vote and actions. Completed
  and cancelled entries expand their existing `MeetingPollResults` inline
  through the row's result button. Narrow containers use item cards with
  left labels and right values, including the conference side panel.
- `MeetingPollPanel` retains active voting, polling/refresh, mutations,
  pagination, confirmations, error handling and permission conditions.
  Existing create/edit/open/cancel handlers are passed to the history view.
  Server result visibility remains authoritative. No API or backend changes.
- Validation: all 115 frontend tests, TypeScript, targeted formatting and
  the isolated production build passed. All 32 browser checks passed using
  mocked reads, including result expansion, pagination, editing, active
  ballot selection, permissions, 320/390px phones, tablets, narrow panels,
  and brand destinations across management pages and the room heading.
  Evidence: `../meeting-service/target/poll-table-home-verification/REPORT.md`.
- Browser verification uses an offline fixture and a Next link/navigation
  shim. No real poll writes, media connections or deployment were performed.

Change log — 2026-10-06: Made brand names return to the public homepage and
changed poll history from full result cards to a responsive table.

## Add members from the meeting edit page (2026-10-06)

- The edit form and participant picker now offer CHAIRMAN, SECRETARY and
  MEMBER for both public and private meetings. After a chairman exists, all
  newly selected people default to MEMBER, including multiple selections.
- Shared rules in `lib/meetingParticipantRules.ts` keep role choices and
  submit validation consistent. Existing public guests retain their persisted
  identity and role when other members are added; GUEST is not offered for
  new invitations. Changing room type while editing retains selected members.
- PUBLIC/PRIVATE creation now follows the create-role behavior described above.
  Existing edit secretary behavior and the single-chairman rule are preserved.
  The participant API already accepts MEMBER; participant synchronization
  behavior remains unchanged.
- Validation: all 115 frontend tests, TypeScript checking and the isolated
  production build passed. Eight browser scenarios passed using mocked data,
  including adding two members, editing roles, retaining a joined guest,
  creation/private-room regressions and a 390px phone flow. Evidence is in
  `../meeting-service/target/participant-role-verification/REPORT.md`.
- Browser saves use an offline fixture and API tests mock HTTP. These checks
  do not confirm a deployed release or writes against a live database.

Change log — 2026-10-06: Enabled adding members on the meeting edit page and
aligned picker defaults, role editing and save validation.

## Phone list item cards with left labels and right values (2026-10-05)

- At widths up to 767px, the meetings and minutes lists use one item card per
  record. The title/code forms the card header. Each information row has a
  left label and a right value, with consistent column proportions and a
  horizontal divider. Phone action footers retain all existing buttons.
- Dates, participant avatars/counts, room types, statuses, chairman details
  and update/version information stay within the right column and wrap as
  needed. Minutes participant counts are also visible through the existing
  avatar wrapper's aria-label, using CSS generated content.
- Only the two list CSS modules changed. Saved-baseline checks confirm the
  non-phone CSS and all three involved TSX components are unchanged. Meeting
  detail, tablet layouts, handlers, APIs and permissions were not edited.
- Validation: 40 browser layout cases with short/long mocked data; geometry
  checks verify right-column placement on phones. Seven filter, pagination
  and menu checks passed at 320px. All 97 frontend tests, targeted formatting
  and the isolated production build passed. Results are recorded in
  `../meeting-service/target/phone-item-cards/REPORT.md`.
- This is a source change; the public deployment and physical iOS testing
  are outside this verification.

Change log — 2026-10-05: Changed the two phone lists to item cards with labels
on the left and values on the right, preserving functional code.

## Meeting and minutes card refinement (2026-10-05)

- Meetings in the responsive list have separated title, schedule, participant,
  access/status and action sections. Four data-label attributes provide the
  responsive field captions. Phone actions stay in one row with 44px heights;
  every card, including the last one, keeps its complete border.
- Meeting detail information and participants have individual bordered groups
  on phones/tablets. Phone labels sit above their values; participant names,
  emails and role badges align beside the avatar and allow long text to wrap.
- Minutes cards have horizontal separators, a full-width schedule and separate
  action footer. Tablets with CSS subgrid support align all field rows across
  each adjacent pair of cards. Other browsers retain the ordinary card layout.
- This refinement changes three CSS modules and presentation attributes on
  MeetingsListPage only. A saved-baseline comparison confirms no TSX logic
  change; MeetingDetailPage and MinutesTable are unchanged.
- Validation: 48 browser layout cases across three screens, eight viewport
  sizes and short/long fixture data; tablet field alignment and ten interaction
  checks passed. All 97 frontend tests, TypeScript, targeted formatting and an
  isolated production build passed. Browser data is mocked; these checks do
  not claim a production deployment or physical iOS device test.
- Evidence: `../meeting-service/target/mobile-card-refinement/REPORT.md`.

Change log — 2026-10-05: Refined meeting list/detail and minutes list cards,
separators and field alignment without changing functional logic.

## Responsive management pages (2026-10-05)

- Shared management navigation switches to the existing drawer at widths up to
  1100px. The shell provides 44px control targets and 16px form text at this
  breakpoint. Menu items continue to follow the existing system-role rules.
- Dashboard, meetings list/detail/create/edit, minutes list/detail/editor,
  users and settings adapt their grids, banners, actions and spacing for phones
  and tablets. Banners grow with their content. Phone meeting summaries and
  agendas display their values and titles without squeezing them into icon rows.
- Minutes and user tables become labeled cards at widths up to 1100px, retaining
  every field and action. Minutes participant tables become cards on phones;
  meeting information is arranged as label/value pairs. Desktop tables remain.
- Participant, minutes history and export dialogs fit the dynamic viewport and
  support scrolling on short screens. Document and Poll controls have larger
  touch targets. The public home uses a compact phone hero, 16px entry text and
  larger tablet header controls; its existing header alignment is preserved.
- Production changes are CSS plus data-label attributes on three existing TSX
  components. A comparison against the task's saved baseline, after removing
  those attributes and normalizing formatting, confirms the TSX contents are
  otherwise unchanged. APIs, handlers, permissions and room/media logic were
  not edited.
- Validation: 97 tests passed; TypeScript and targeted formatting passed;
  isolated production build passed. Browser checks cover nine management screens
  at nine viewport sizes (81 combinations), plus the actual Next homepage at
  seven sizes. No horizontal content overflow was detected outside deliberate
  scroll containers. Existing two-line dashboard title previews remain on
  desktop. Filters, pagination, role menus, participant selection, minutes editing,
  history and export preview were exercised with isolated fake data.
- Evidence and reproduction files:
  `../meeting-service/target/responsive-ui-verification/REPORT.md`.
  Internal UI verification does not replace real backend/media integration
  testing or Safari testing on a physical iPhone/iPad. No production deployment,
  data mutation, hardware access or application configuration change occurred.

Change log — 2026-10-05: Optimized management and public-home layouts across
phone, tablet and desktop sizes, preserving existing functional code.

## Collapsible conference toolbar (2026-10-05)

- `ManagementToolbarVisibility.tsx` adds a top-right × to the actual LiveKit
  toolbar and an up-arrow above the bottom-right participant metadata after collapse. The same
  button retains keyboard focus and exposes aria-expanded/aria-controls; both
  states provide a 44px touch target. Toolbar height is measured so the close
  control stays at its corner as controls wrap or the room width changes.
- Toolbar collapse uses CSS display:none on the existing toolbar. Its SDK and
  management controls remain mounted, retaining media state, capture polling,
  pending actions and panel selection. The video area fills the released space.
  Visibility is local to the mounted conference and defaults to expanded.
- Arrow refinement (2026-10-07): the restore action shows only its up-arrow SVG;
  its visible text, border and background surface are removed. It retains the
  inherited 44px target, accessible label/title and existing collapse/restore
  handler. TypeScript, targeted formatting and browser click/Enter checks passed
  using the real component/styles with SDK/network stubs. Evidence:
  `../meeting-service/target/toolbar-restore-arrow-verification/`.
- Hover refinement (2026-10-07): the restore action is 56px above the video
  frame's bottom edge, clearing the SDK connection-quality icon. On fine-pointer
  devices with hover it appears only while the video host is hovered or the
  button has visible keyboard focus. Touch/coarse-pointer devices retain a
  visible 44px restore action. The expanded close button and all handlers remain
  unchanged. TypeScript, targeted formatting and offline browser checks with
  actual LiveKit components/styles passed: outside/inside hover, no icon overlap,
  Enter/click restore, touch emulation at a requested 320px viewport, and desktop
  Poll panel retention with all 11 toolbar button nodes still present. Evidence:
  `../meeting-service/target/toolbar-hover-verification/`. Connected media and
  physical-device testing were outside this cosmetic verification.
- `ManagementMediaInitializer.tsx` uses the room bottom as its notification
  boundary when the toolbar is hidden, preserving visible device warnings.
- Validation: 97 frontend tests, TypeScript, targeted formatting and isolated
  production build passed. Real SDK UI tested offline at 320x568, 390x844 and
  1440x900: collapse/restore by click, Enter and Space, focus retained, no control
  overlap or horizontal overflow. Toolbar DOM controls persist and microphone
  stub attempt count remains 1. An open Poll side panel retains its selection
  during collapse. The 320px restore action is visible at y=505..549.
  No hardware access, actual capture action, backend mutation or room connection
  was performed. Evidence: `../meeting-service/target/toolbar-visibility-verification/`.

Change log — 2026-10-05: Added collapsible conference controls with a persistent
restore action, preserving mounted controls and device warnings.

## Mobile home header alignment (2026-10-05)

- At widths up to 768px, `Home.module.css` places the brand on its own line and
  the mode switch/account action on a single row below. The brand uses nowrap;
  navigation and authentication actions no longer wrap.
- `HomeImmersive.module.css` removes the mobile order override that placed the
  mode switch after login. Mobile switch buttons and header account actions
  provide at least 44px touch height. Desktop layout and action handlers are
  unchanged.
- Validation: targeted CSS formatting passed. Actual Next development homepage
  checked at 320x568, 390x844, 768x1024 and 1440x900: brand text has one line and
  mode/login centers align; no horizontal overflow. Switched to 3D and back at
  390px; layout and active button states remain correct. Login destination remains
  `/auth/login?returnTo=%2Fmanagement`. Evidence:
  `../meeting-service/target/home-header-verification/`.

Change log — 2026-10-05: Kept the mobile home brand on one line and placed mode
switch/login controls together on the next row, with usable touch targets.

## LiveKit toolbar styling (2026-10-07)

- Poll, Q&A, Documents, capture and end-meeting buttons use the SDK's `lk-button`
  class for native background, hover/pressed/disabled styling, font and padding.
  `ManagementRoomToolbarIcon.tsx` shares 16px SVGs across these controls. Visible
  captions follow the SDK's 760px minimal breakpoint and 1000px breakpoint while
  Chat is open; accessible names/titles remain available when captions are hidden.
- Leave styling is scoped to this room toolbar and uses the normal SDK control
  palette without the old danger border/bold weight. The toolbar × has no border
  or idle background. Capture retains a green record dot/red stop symbol and all
  existing pending, completed and retry captions/disabled rules. Duplicated custom
  button skins and the obsolete capture glow animation were removed.
- Toolbar flex wrapping and automatic device-group height apply at all widths.
  Video/grid space follows the measured toolbar height rather than its native
  fixed single-row height, preventing clipping when custom controls wrap. The
  existing reserved space for ×, dock positioning and collapse behavior remain.
- Validation: TypeScript, targeted formatting and offline browser checks passed
  using real SDK VideoConference/controls in an offline Room context with mocked
  device methods/API. Desktop at actual 1267px wraps cleanly; a requested 1920px
  view uses one row; requested 320px touch view uses icon buttons without overflow
  or × overlap. Tablet Chat switches captions as native controls do. Poll/Q&A/
  Documents/Chat switching, capture start/stop, toolbar collapse/Enter restore and
  member-role button visibility were checked. The original end action reached
  only the local API stub. No connected media, production deployment or real
  meeting mutation was performed. Evidence:
  `../meeting-service/target/room-controls-verification/`.

## Collapsible media notifications (2026-10-05)

- `ManagementMediaNotice.tsx` provides an independent close/restore control for
  microphone, camera and device warnings. The top-right × collapses a card into
  its icon; clicking or pressing Enter/Space on the icon restores the details.
  Controls retain focus and expose their expanded state. Expanded × controls
  provide 44px hit targets; the latest compact-icon sizing is described below.
- `ManagementMediaInitializer.tsx` renders device warnings alongside its media
  warnings in a top-left stack. Collapsed notices share a bottom-left icon dock.
  A ResizeObserver keeps the dock above the actual LiveKit toolbar and participant
  metadata, and limits expanded cards to the space above that dock. Long content
  scrolls on small screens.
- Refinement (2026-10-07): the audio-ready notification also uses the shared × /
  restore control, with a green microphone icon when collapsed. Warnings retain
  their individual icons and states. Portals move only notice presentation to the
  dock; initialization, device retry and media status stay in the existing owner.
  Focus follows the moved control; restoring a card allows its scrollable stack
  to reveal the focused ×, while collapsing keeps the meeting viewport stable.
- Refinement verification: TypeScript and targeted formatting passed. Actual
  installed LiveKit components were checked offline on desktop and a requested
  320x568 touch viewport: mixed expanded/collapsed cards, independent warning
  icons, success collapse/restore, Enter/Space, focus retention, scrollable long
  notices, toolbar show/hide, and no icon overlap with metadata or toolbar. Only
  explicit retries incremented stub microphone/camera calls; all 11 toolbar
  button nodes remained. Evidence:
  `../meeting-service/target/media-notice-dock-verification/`. Physical-device and
  connected media testing were outside this presentation verification.
- Compact-icon refinement (2026-10-07): collapsed notices use borderless,
  transparent 24px controls with 16px SVGs, matching the native video icons.
  Coarse-pointer devices retain a transparent 44px touch target around that same
  16px SVG. The dock, restore focus and independent collapse states are preserved.
  Evidence: `../meeting-service/target/room-controls-verification/`.
- Collapsing only changes presentation. Existing microphone initialization,
  fallback, retry, camera and room callbacks retain their behavior. A changed
  warning message receives a fresh expanded notice; retry outcomes use the
  existing starting/error/ready states. Collapse preferences last for the
  mounted notice and are not saved across page reloads.
- Validation: 97 frontend tests, TypeScript, targeted formatting and an isolated
  production build passed. Real components checked in an offline browser fixture
  at 320x568, 390x844 and 1440x900. Both notices collapse independently and restore;
  Enter works, focus is retained, collapse causes no microphone attempt, and
  simulated failed/successful retries preserve the other notice's collapsed state.
  At 320x568 the scrollable stack ends at y=384, above the toolbar at y=396.
  No real microphone access, backend mutation or LiveKit connection was used.
  Evidence: `../meeting-service/target/media-notice-verification/`.

Change log — 2026-10-05: Added × and restorable icons to media warnings, grouped
them to prevent overlap and kept the toolbar accessible on small screens.

## Mobile authentication and room layout (2026-10-05)

- Auth and join pages own a viewport-height vertical scroll area. They no longer
  rely on the globally locked body to scroll long forms or prejoin content.
- Mobile auth uses a compact, content-sized visual header, 16px input text,
  44px password controls and safe-area padding. Both forms remain mounted; the
  existing mode, validation, session and submission behavior is preserved. The
  tablet breakpoint now covers 768px without a gap.
- `MeetingRoomClient.tsx` adds a presentation-only `connectedPage` class when the
  conference UI is rendered. Header and toolbar stay within the viewport; short
  landscape windows can scroll a minimum-height room. Prejoin and guest content
  use natural height, with a smaller preview and two device controls on mobile.
- LiveKit and management toolbar controls wrap on phone/tablet and have at least
  44px touch targets. Mobile chat and document/Poll overlays stay within the room;
  fullscreen side panels retain usable width. Long titles are capped to two lines
  in the connected mobile header; participant labels truncate instead of overflow.
- Changes are limited to `AuthPages.module.css`, `ManagementRoom.module.css`, the
  room page's CSS class and this context file. No API, permission, microphone
  consent, signaling, capture, meeting termination or voting behavior changed.
- Validation: 97 frontend tests, TypeScript and production build passed (isolated
  `.next-responsive-verification` output; original Next/TypeScript config restored).
  Real auth pages checked at
  320/360/390/430/768/844/1440px and a reduced 390x400 viewport; submit controls
  are reachable and there is no horizontal overflow. Join/room layouts checked
  using real components in an isolated offline fixture with hardware disabled.
- Public `/auth` verified at 390x844: active registration form, content-sized
  142px visual header, vertical scrolling and no horizontal overflow. Short
  844x390 prejoin fits its join action at y=324px; 320x568 room with very long
  labels retains toolbar at y=557px and a two-line title.
- Physical iOS Safari keyboard/safe-area behavior and connected media acceptance
  still require device verification. Offline layout checks do not certify LiveKit
  network connectivity. Evidence: `../meeting-service/target/responsive-preview/`.

Change log — 2026-10-05: Fixed mobile auth/prejoin clipping and room controls,
including tablet and short landscape layouts; preserved existing business flows.

## Poll / Biểu quyết (2026-10-05)

- `components/management/polls/MeetingPollPanel.tsx` is shared by meeting detail and
  the LiveKit side panel. `ManagementPollBridge.tsx` adds the toolbar control while
  preserving the existing Room, media and capture lifecycle.
- `MeetingPollEditor.tsx` manages drafts and an explicit electorate;
  `MeetingPollBallot.tsx` restores server-confirmed choices and validates limits;
  `MeetingPollResults.tsx` displays server-filtered totals/percentages.
- `services/management/managementPollService.ts` and `types/management/poll.ts`
  use Java `/api/meetings/{id}/polls` through the existing same-origin session/CSRF
  proxy. Vote PUT `/{pollId}/my-vote` sends optionIds only. No caller-supplied voter ID.
- Active/final data refresh every 3 seconds, history/context every 15 seconds and
  after actions/focus/online. Hidden/unmounted panels stop periodic work. The request
  coordinator ignores stale responses; no optimistic vote or client tally is used.
- Draft/configuration/action access and result visibility are determined by Java.
  Closed history is persisted data, separate from the existing demo activity card.
- 97 frontend tests and TypeScript passed; production build passed with existing
  non-Poll warnings. Actual management UI verified create/open/vote/reload/close,
  multi-choice limits/change and server results on isolated data.
- Connected-room toolbar/media/fullscreen/mobile acceptance remains pending the
  user's microphone choice. Application DB migration/deployment has not occurred.
  Full evidence and next work: `../meeting-service/docs/POLL_IMPLEMENTATION.md`.
- Two independent room header/error edits observed during this work were preserved.

Change log — 2026-10-05: Added Poll P4–P6 frontend and P7 management UI verification;
added Vitest path alias/automatic JSX configuration for actual component tests.

## 2.1 Authentication

Management routes are protected by:

```text
components/management/ManagementAuthGuard.tsx
app/management/layout.tsx
contexts/ManagementAuthContext.tsx
```

Unauthenticated access to `/management/*` redirects to login.

Canonical authentication UI route:

```text
/auth
```

Compatibility routes remain available and redirect to the canonical route while preserving query parameters:

```text
/auth/login    -> /auth?mode=login
/auth/register -> /auth?mode=register
```

Login/Register switching is local React state inside `ManagementAuthExperience`; it does not navigate or remount the auth experience. The `mode` query parameter mirrors the current local mode through the native History API so existing query parameters such as `returnTo` are preserved without creating browser history entries. Both auth forms stay mounted on desktop, and one persistent visual panel receives its current `transform` directly from local mode while CSS controls transition timing. The auth API/session/CSRF contract is unchanged.

## 2.2 Meeting roles

Current meeting roles:

```text
CHAIRMAN
SECRETARY
MEMBER
GUEST
```

PUBLIC meeting:

- Chairman/Secretary are explicitly assigned.
- Public-link user may join as guest.

PRIVATE meeting:

- Requires authenticated/invited participant according to backend rules.

Dynamic RBAC is deferred.

## 2.3 Meeting room

Main room route:

```text
/management/rooms/{meetingId}
```

LiveKit UI is rendered through project room components.

Important behavior:

- Do not remount LiveKit when opening side panels/fullscreen.
- Documents and Chat should remain usable inside the room experience.
- Microphone permission may fail on HTTP IP addresses; localhost/HTTPS is preferred.
- Multiple tabs using the same participant/session may disconnect the earlier connection.

## 2.4 Documents

Documents integration is already considered stable.

Important files:

```text
types/management/documents.ts
services/management/managementDocumentService.ts
components/management/documents/MeetingDocumentsList.tsx
components/management/documents/MeetingDocumentUpload.tsx
components/management/documents/MeetingDocumentsPanel.tsx
components/management-room/ManagementDocumentsBridge.tsx
styles/management/Documents.module.css
```

Supported usage:

- Meeting creation attachments.
- Meeting detail upload/list/view/download.
- Room document side panel.
- Chat paperclip upload.
- Minutes detail documents.

Document source values:

```text
BEFORE_MEETING
IN_MEETING
AFTER_MEETING
```

Do not change Documents unless a task explicitly requires it.

## 2.5 Chat persistence

Realtime chat is still LiveKit chat.

Persistence bridge:

```text
types/management/chat.ts
services/management/managementChatService.ts
components/management-room/ManagementChatPersistenceBridge.tsx
```

Behavior:

```text
User submits LiveKit chat
       │
       ├── LiveKit realtime message continues normally
       │
       └── FE POSTs message to Java for persistence
```

The bridge must NOT:

- `preventDefault()`
- stop LiveKit chat propagation
- resend a LiveKit message during persistence retry

Persistence uses:

- `clientMessageId`
- message content
- `X-Meeting-Participant-Token`

Retry uses the same `clientMessageId` so Java can guarantee idempotency.

Chat is later used by Java when building the Meeting Timeline.

## 2.6 Minutes routes

```text
/management/minutes
/management/minutes/[meetingId]
```

Minutes detail currently supports:

```text
NOT_GENERATED
PROCESSING
FAILED
DRAFT
FINAL
```

### NOT_GENERATED

Show:

```text
Chưa có biên bản
[Tạo biên bản]
```

Opening the tab MUST NOT create a Minutes job.

Only clicking `[Tạo biên bản]` calls:

```http
POST /api/meetings/{meetingId}/minutes/generate
```

### PROCESSING

Show:

```text
Đang tạo biên bản
stage
progressPercent
message
```

Do NOT render a blank Minutes form while no version exists.

FE polls:

```http
GET /api/meetings/{meetingId}/minutes/processing
```

Current polling interval:

```text
2000 ms
```

A future optimization may increase this to about 5000 ms.

On page reload:

- If no Minutes version exists, check current processing state.
- `QUEUED/PROCESSING` → restore PROCESSING UI.
- `FAILED` → restore FAILED UI.
- No active job → NOT_GENERATED.

### FAILED

Show:

- error message
- `[Thử lại]`

### DRAFT / FINAL

Only when a Minutes version exists:

- render Minutes content
- version/history
- editing/finalization actions according to current rules

Buttons:

```text
Chỉnh sửa
Tạo phiên bản mới
Hoàn tất biên bản
```

must only render when versions exist.

---

# 3. API contracts used by FE

## Q&A

Base: `/api/meetings/{meetingId}/qa`. Services use the existing same-origin
session/CSRF client. Room requests carry `X-Meeting-Participant-Token` from
`join.participant.chatWriteToken`; an invalid supplied token has no session
fallback. Ask/upvote require a verified active room participant. Authorized
signed accounts can read history without a token; moderator writes/report also
require the server's session-based moderator authorization.

| Method | Route                     | Input / response                                                                                                                                   |
| ------ | ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| GET    | `/context`                | enabled, anonymousAllowed, moderationEnabled, settingsVersion, meetingStatus, canManage, canAsk, canUpvote                                         |
| GET    | `/questions`              | page, size=20, sort=NEWEST/OLDEST/POPULAR, filter=ALL/MINE/PENDING; page response uses content, page, size, totalElements, totalPages, first, last |
| POST   | `/questions`              | clientQuestionId UUID, trimmed content (1–2000 characters), anonymous                                                                              |
| PUT    | `/questions/{id}/upvote`  | upvoted boolean; server-confirmed desired state/count                                                                                              |
| PUT    | `/settings`               | enabled, anonymousAllowed, moderationEnabled, expectedVersion                                                                                      |
| POST   | `/questions/{id}/actions` | APPROVE/ANSWER/HIDE/DELETE, expectedVersion                                                                                                        |
| GET    | `/report`                 | Moderator-only text/csv UTF-8 BOM; six business columns, all persisted statuses, anonymous names remain hidden                                     |

Question DTO: id, own clientQuestionId, content, anonymous, authorDisplayName,
status, createdAt, updatedAt, answeredAt, upvoteCount, upvotedByMe, mine, version.
Statuses are PENDING/OPEN/ANSWERED/HIDDEN/DELETED. ClientQuestionId is never
rendered; neither participant/account identity nor moderator audit IDs are UI
question fields. The client uses server-filtered pagination and counts.

## Minutes

```http
GET  /api/minutes
GET  /api/minutes/summary

GET  /api/meetings/{id}/minutes/current
GET  /api/meetings/{id}/minutes/versions
GET  /api/meetings/{id}/minutes/versions/{version}

POST /api/meetings/{id}/minutes/versions
PUT  /api/meetings/{id}/minutes/versions/{version}
POST /api/meetings/{id}/minutes/versions/{version}/finalize

POST /api/meetings/{id}/minutes/generate
GET  /api/meetings/{id}/minutes/processing
POST /api/meetings/{id}/minutes/retry
```

FE never calls Python STT endpoints directly.

## Chat persistence

Current Java endpoint:

```http
POST /api/meetings/{meetingId}/chat/messages
```

Body:

```json
{
  "clientMessageId": "...",
  "content": "..."
}
```

Header:

```text
X-Meeting-Participant-Token: <chatWriteToken>
```

---

# 4. Target Minutes user flow

```text
Open Minutes detail
        ↓
No version?
        ↓
Check processing state
   ┌────┴────┐
   │         │
No job    Active job
   │         │
   ▼         ▼
Chưa có   Đang tạo
biên bản  biên bản
   │
[Tạo biên bản]
   │
POST Java /minutes/generate
   │
202
   │
PROCESSING UI
   │
poll Java
   │
COMPLETED
   │
reload current/versions
   │
render Minutes V1
```

---

# 5. Current status

```text
Meeting management                      ✅
LiveKit room                             ✅
Auth guard                               ✅
Documents                                ✅
Realtime chat                            ✅
Chat persistence bridge                  ✅
Minutes list/detail                      ✅
Generate button                          ✅
PROCESSING state                         ✅
FAILED state                             ✅
Processing polling                       ✅
Reload processing recovery               ✅
Final Minutes version UI                 ✅

Real Transcript tab integration           ❌
Python STT direct integration in FE       NOT NEEDED
```

---

# 6. Known constraints

- Q&A permissions, privacy, counts, question state and version conflicts are
  authoritative in Java. Peer packets are only bounded reload hints.
- Anonymous Q&A asks must not publish client peer hints: the LiveKit packet
  sender would reveal authorship. Never put question content/IDs or tokens in
  the Q&A hint payload.
- Completed/cancelled Q&A is read-only. Disabling Q&A preserves history;
  disabling moderation does not approve pending questions automatically.
- Disconnected guests have no tokenless Q&A history. Signed ordinary members
  use the dedicated Q&A route rather than broadening generic meeting-detail
  permissions. CSV export remains moderator-only and preserves anonymity.
- Opening Minutes does not create a job.
- FE is not the source of truth for Minutes processing; Java is.
- FE must not poll Python directly.
- Chat is already text; it must not be sent to STT.
- Documents are outside the current STT task.
- Do not fake processing percentages in FE.
- If Java reports PROCESSING, FE should display it even after reload.
- If no version exists, do not render editable Minutes content.

---

# 7. Next work

Frontend work is currently mostly waiting for backend pipeline completion.

After Java/Python pipeline produces real data:

1. Map Java processing stages/messages to the existing PROCESSING UI.
2. Load actual Transcript API into the `Bản ghi lời nói` tab.
3. On Minutes job completion, reload current/version and show generated V1.
4. Optionally increase processing polling interval if needed.

---

# 8. Codex update template

After modifying this repository, append/update:

```text
## Change log

### YYYY-MM-DD — <task name>

Files added:
- ...

Files modified:
- ...

Behavior changed:
- ...

API contract changed:
- ...

Tests:
- ...

Next:
- ...
```

Do not document work performed only in Java or Python unless it changes an FE-facing contract.

### 2026-10-06 — Pure-black centered meeting loading

- Updated the two loading renders in `MeetingRoomClient.tsx` and added scoped
  `loadingPage` rules in `ManagementRoom.module.css`; no join state/API changes.
- Unified Vietnamese loading copy and status semantics. Passed 177 tests,
  TypeScript/format, exact source review and 8 browser cases with controlled mock
  response release. Evidence: `meeting-service/target/meeting-loading-verification`.

### 2026-10-06 — Transparent prejoin information header

- Removed the header from the shared card surface rule and made its scoped prejoin
  background transparent with no border/shadow. Content, buttons and logic unchanged.
- Format, independent source review and 3 browser cases PASS; evidence in
  `meeting-service/target/prejoin-space-verification/header-transparent-*`.

### 2026-10-06 — Complete the prejoin space backdrop

- Added `ManagementPreJoinBackdrop.tsx`, `PreJoinBackdrop.module.css` and a local
  SVG planet texture; existing route only imports/renders it before connection,
  and the prejoin modifier adds position/isolation.
- Added two side planets, orbit arcs and three floor rings on desktop; mobile
  reduces decoration. The layer cannot receive clicks or focus and is removed
  when entering the room. No business handlers, requests or device settings changed.
- Passed 177 tests, TypeScript/format, 6 browser cases and exact source review.
  Screenshots/evidence: `meeting-service/target/prejoin-space-verification`.

### 2026-10-06 — Match prejoin to the guest entry palette

- Added only a conditional `preJoinPage` class in `MeetingRoomClient.tsx` and scoped
  theme CSS in `ManagementRoom.module.css`; updated this context.
- Preserved prejoin/device/join handlers and connected-room styling. Passed 177
  frontend tests, TypeScript/format, 9 browser cases and independent source review.
- Real SDK controls were tested with synthetic media and mock HTTP. Screenshots
  and limitations are documented in `meeting-service/target/prejoin-theme-verification`.

### 2026-10-06 — Dark Space guest meeting entry

- Added `styles/management/GuestJoin.module.css`; updated `ManagementGuestJoin.tsx`,
  the guest render wrappers in `MeetingRoomClient.tsx`, and removed unused guest
  selectors from `ManagementRoom.module.css`.
- Updated guest-entry visuals only. No API, validation, guest identity, join/media
  or navigation behavior changed; private authentication retains its current UI.
- Passed 177 frontend tests, TypeScript, targeted formatting, 15 browser cases and
  independent source review. Phone/desktop screenshots and mock-boundary evidence
  are kept in `meeting-service/target/guest-entry-verification`.

### 2026-09-03 — Unified auth page and sliding panel

Files added:

- `app/auth/page.tsx`

Files modified:

- `app/auth/login/page.tsx`
- `app/auth/register/page.tsx`
- `components/auth/ManagementAuthExperience.tsx`
- `styles/management/AuthPages.module.css`
- `PROJECT_CONTEXT.md`

Behavior changed:

- `/auth` is the canonical authentication page.
- `/auth/login` and `/auth/register` redirect to `/auth?mode=...` while preserving existing query parameters such as `returnTo`.
- Login/Register switching uses local state and does not navigate.
- Desktop auth presentation keeps both forms mounted and moves a single visual panel with CSS `transform`.

API contract changed:

- None.

Tests:

- `npm test` passed outside sandbox: 10 files, 63 tests.
- `npx tsc --noEmit` passed.
- `npm run build` passed outside sandbox with existing warnings.

Next:

- None.

### 2026-09-03 â€” Auth panel deterministic transform path

Files added:

- None.

Files modified:

- `components/auth/ManagementAuthExperience.tsx`
- `styles/management/AuthPages.module.css`
- `PROJECT_CONTEXT.md`

Behavior changed:

- URL mode synchronization remains unchanged.
- The single auth visual panel now receives its current transform directly from local mode on the same DOM element.
- CSS no longer uses mode-specific visual panel transform classes; the base panel class owns transition timing.
- Mobile keeps the existing non-sliding panel presentation.

API contract changed:

- None.

Tests:

- `npm test` passed outside sandbox: 10 files, 63 tests.
- `npx tsc --noEmit` passed.
- `npm run build` hung after the Next.js startup banner and was interrupted.

Next:

- Runtime browser visual verification is still required.

### 2026-09-03 — Auth mode URL sync and panel slide fix

Files added:

- None.

Files modified:

- `components/auth/ManagementAuthExperience.tsx`
- `styles/management/AuthPages.module.css`
- `PROJECT_CONTEXT.md`

Behavior changed:

- Login/Register mode changes now mirror `mode=login|register` into the current `/auth` URL with `window.history.replaceState`.
- Existing query parameters, including `returnTo`, and URL hash are preserved during mode sync.
- Mode switching still uses local React state and does not use Next route navigation.
- The visual panel uses explicit login/register transform classes while remaining one persistent DOM element.

API contract changed:

- None.

Tests:

- `npm test` passed outside sandbox: 10 files, 63 tests.
- `npx tsc --noEmit` passed.
- `npm run build` hit `.next/trace` EPERM in sandbox; outside-sandbox rerun hung after the Next.js startup banner and was interrupted.

Next:

- None.

### 2026-10-07 — Responsive creation fields and single save action

- Modified `MeetingForm.tsx`, `MeetingDocumentUpload.tsx`,
  `MeetingPages.module.css` and `Documents.module.css`.
- Grouped date/time fields on desktop, retained mobile stacking, enlarged the
  bright blue file icon, removed the intro and duplicate bottom actions.
- API contracts unchanged. TypeScript, formatting, 228 tests, source-scope
  comparison and desktop/mobile browser checks passed. Mock creation with one
  document and the existing success popup passed.

### 2026-10-08 — Point meetings and speaking history

- Added POINT creation choice on home and management menus, scheduled PUBLIC
  form with validated point names, optional chair/secretary roster, and creation popup.
- Require point confirmation before issuing a join token; retain a valid prior
  choice and secure guest resume proof. Late joins cannot cross account/session state.
- Added speaking toolbar/panel and persistent history route: optional shared-device
  speaker name, request/cancel, creator/chair/secretary approval, requester/moderator finish.
  Actions record server UTC timestamps and leave microphone behavior unchanged.
- HTTP identity/capability state is authoritative. Room packets only request a
  bounded refresh; reads/writes have deadlines, cancellation and stale-result guards.
- POINT metadata/points/roster update together. Creation uses a durable actor-scoped
  UUID receipt; pending metadata can be explicitly restored after reload without an
  automatic POST. Confirmed creations keep their link even when later detail reads fail.
- Corrected form schedule parsing so local backend times do not shift during edits.
- Final frontend verification: 302 tests PASS, TypeScript PASS, scoped formatting
  PASS, production build PASS. Existing unrelated build warnings remain.
- Browser checks use synthetic HTTP/room fixtures, including lost-response recovery,
  normal meeting toolbar and 390px mobile overflow check. Evidence/images:
  `../output/point-meeting-verification/verification.json`.
- Backend contract, V19 rollout and exact Java verification:
  `../meeting-service/docs/MEETING_POINTS.md`. No deployment or application DB changes.

### 2026-10-08 — Participant card layout

- Creation form role badge now sits beside the participant name. Edit/remove
  actions use a dedicated footer aligned to the card's right edge, preserving
  the shared meeting-card footer and the existing callbacks.
- Long names/emails/IDs wrap within the card on mobile. TypeScript and scoped
  formatting passed. Synthetic browser verification passed on desktop and
  390px mobile, including long text, opening/cancelling edit and removing a
  sample participant. Screenshots: `../output/participant-layout-verification/`.

### 2026-10-08 — Production audit frontend corrections

- Enabled LiveKit adaptive streaming/dynacast. `ManagementConferenceLayout`
  retains SDK tiles, screen-share focus, controls, chat classes and connection
  toast, with exactly one `RoomAudioRenderer`. The stock non-persistent chat
  and submit-event persistence bridge have been replaced by authoritative API chat.
- Chat POST is confirmed by Java before any LiveKit broadcast. Stable client IDs
  survive uncertain retries in the current connection; canonical history receipts
  reconcile uncertain sends without clearing new text the user has edited.
  Peer packets only request a bounded reload. After reconnect/reload the latest
  100 records are loaded; incremental reads retain at most 500 recent messages
  in browser memory. Full durable DB history remains intact. A confirmed legacy
  packet also supports existing deployed prefab clients during rollout.
- Intentional Leave still returns home. Final server/network disconnects retain
  an explanation and an explicit retry that revalidates the meeting, permissions,
  point and join credentials. Deleted rooms and removed participants are terminal.
- All creation modes now submit actor-scoped UUIDs and retain unknown-write
  metadata across reload. POINT uses its existing storage key for compatibility.
  Recovery never restores File objects or invents ownership, and requires deliberate
  user acknowledgement. Deleted receipts clear only the matching draft.
- All meeting edits send one atomic metadata/roster PUT with the observed
  participant-key/role baseline. Documents remain separate operations: failures
  report that meeting data was saved, retain actual receipts and reload real state.
  API end/delete/admin-override 202 receipts explicitly report pending connection
  cleanup. Admin cancellation preserves 204 success and reports pending work with
  the same existing wording; rejected overrides retain their actual error.
- Guest resume identity/proof is persisted together for every PUBLIC mode;
  unproved stored guest IDs are no longer reused. Existing open/public/private
  permissions, point confirmation, menu visibility and speaking workflow are retained.
- Management HTTP requests have a 30-second deadline covering CSRF, dispatch
  and JSON/blob/error-body consumption. Caller cancellation propagates during
  CSRF; no write is dispatched afterwards and no mutation is automatically retried.
  Temporary authentication outages preserve a known session. Rejected credentials
  still clear it; late refresh/logout receipts cannot revive a discarded session.
- Capture polling is single-flight with bounded reads, disposal/cancellation,
  superseded-response protection and coordinated writes. Existing control markup
  and its 3-second polling interval are retained.
- Only safe MIME previews open as blobs; active/unknown content is downloaded
  as octet-stream. Filenames are sanitized. Browser production source maps are
  disabled. Draft minutes PUT includes the observed revision; normal version-create
  UI flow is unchanged.
- Removed fictional agenda/activity data (expanded finding E-FE-01). The same
  cards now show saved description, actual participant joined/left timestamps
  or a truthful empty state.
- Verification: **356 frontend tests PASS**, TypeScript PASS, isolated production
  build PASS (`.next-audit24-production`, build ID `XEFcCb2wqiKyV5n-rw4nj`),
  browser static source-map count **0**. Next-generated tsconfig/next-env edits
  were restored byte-for-byte. Existing docx-preview source-map warnings and
  lint hints remain; capture generation-counter cleanup also has a non-failing
  ref lint hint. No live server, browser session, deployment or load trial was run.
- Evidence: `../output/audit24/frontend-tests.json`, `frontend-build.log`,
  `frontend.md`. Backend API/migration/security verification is owned separately.
