'use client';

import { HomeBrandLink } from '@/components/home/HomeBrandLink';

import { type ReactNode, useRef, useState } from 'react';
import { MeetingDocumentUpload } from '@/components/management/documents/MeetingDocumentUpload';
import { MeetingRoleBadge } from '@/components/meetings/MeetingStatusBadge';
import { MeetingPointFields } from './MeetingPointFields';
import { getMeetingFormDateTime } from '@/lib/meetingFormDateTime';
import {
  parseMeetingPointNames,
  validateMeetingPointNames,
  validatePointMeetingParticipantRoles,
} from '@/lib/meetingPointRules';
import {
  getMeetingParticipantRoleOptions,
  validateMeetingParticipantRoles,
  validateMeetingParticipants,
} from '@/lib/meetingParticipantRules';
import {
  ParticipantPickerDialog,
  type ParticipantPickerSelection,
} from '@/components/meetings/ParticipantPickerDialog';
import type {
  ManagementMeeting,
  ManagementMeetingAccessType,
  ManagementMeetingCreationMode,
  ManagementMeetingDocument,
  ManagementMeetingMutationInput,
  ManagementMeetingParticipant,
  ManagementMeetingRole,
} from '@/types/management/meeting';
import {
  MANAGEMENT_MEETING_ACCESS_TYPE_LABELS,
  MANAGEMENT_MEETING_ROLE_LABELS,
} from '@/types/management/meeting';
import editStyles from '@/styles/management/ManagementMeetingEdit.module.css';
import styles from '@/styles/management/MeetingPages.module.css';

export interface MeetingFormValues extends ManagementMeetingMutationInput {}

interface MeetingFormProps {
  mode: 'create' | 'edit';
  initialMeeting?: ManagementMeeting;
  initialValues?: MeetingFormValues;
  creationMode?: ManagementMeetingCreationMode;
  submitting: boolean;
  onSubmit: (values: MeetingFormValues) => Promise<void>;
  onCancel: () => void;
}

interface FormFieldErrors {
  title?: string;
  meetingDate?: string;
  startTime?: string;
  endTime?: string;
  participants?: string;
  pointNames?: string;
}

function toDateInputValue(value: string | null) {
  return getMeetingFormDateTime(value).date;
}

function toTimeInputValue(value: string | null) {
  return getMeetingFormDateTime(value).time;
}

function combineDateTime(dateValue: string, timeValue: string) {
  return `${dateValue}T${timeValue}:00`;
}

function openNativePicker(input: HTMLInputElement | null) {
  if (!input) {
    return;
  }

  try {
    input.showPicker?.();
  } catch {
    // Browser without showPicker support can still open the picker via the default click.
  }
}

function preventDateTimeTyping(event: React.KeyboardEvent<HTMLInputElement>) {
  const allowedNavigationKeys = ['Tab', 'Shift', 'Escape'];

  if (allowedNavigationKeys.includes(event.key)) {
    return;
  }

  event.preventDefault();
}

type EditIconName =
  | 'arrowLeft'
  | 'calendar'
  | 'check'
  | 'chevronDown'
  | 'clock'
  | 'document'
  | 'file'
  | 'info'
  | 'list'
  | 'lock'
  | 'plus'
  | 'shield'
  | 'trash'
  | 'userPlus'
  | 'users';

const editAgendaItems = [
  {
    title: 'Báo cáo tiến độ các dự án chuyển đổi số',
    duration: '30 phút',
  },
  {
    title: 'Thảo luận đề xuất giải pháp nâng cao hiệu quả vận hành',
    duration: '45 phút',
  },
  {
    title: 'Phân công nhiệm vụ và kế hoạch triển khai quý IV/2026',
    duration: '30 phút',
  },
  {
    title: 'Các vấn đề khác',
    duration: '15 phút',
  },
];

function EditIcon({ name }: { name: EditIconName }) {
  const paths: Record<EditIconName, ReactNode> = {
    arrowLeft: <path d="M19 12H5m0 0 6-6m-6 6 6 6" />,
    calendar: (
      <>
        <path d="M7 3v3" />
        <path d="M17 3v3" />
        <rect x="3.5" y="5" width="17" height="16" rx="2.5" />
        <path d="M3.5 9.5h17" />
      </>
    ),
    check: <path d="m5 12 4 4L19 6" />,
    chevronDown: <path d="m6 9 6 6 6-6" />,
    clock: (
      <>
        <circle cx="12" cy="12" r="8.5" />
        <path d="M12 7.5V12l3 2" />
      </>
    ),
    document: (
      <>
        <path d="M6 3.5h8l4 4v13H6z" />
        <path d="M14 3.5v4h4" />
        <path d="M9 12h6" />
        <path d="M9 16h5" />
      </>
    ),
    file: (
      <>
        <path d="M7 3.5h7.5L18 7v13.5H7z" />
        <path d="M14.5 3.5V7H18" />
      </>
    ),
    info: (
      <>
        <circle cx="12" cy="12" r="8.5" />
        <path d="M12 11v5" />
        <path d="M12 8h.01" />
      </>
    ),
    list: (
      <>
        <path d="M8 6h12" />
        <path d="M8 12h12" />
        <path d="M8 18h12" />
        <path d="M4 6h.01" />
        <path d="M4 12h.01" />
        <path d="M4 18h.01" />
      </>
    ),
    lock: (
      <>
        <rect x="5" y="10" width="14" height="10" rx="2" />
        <path d="M8 10V7a4 4 0 0 1 8 0v3" />
      </>
    ),
    plus: (
      <>
        <path d="M12 5v14" />
        <path d="M5 12h14" />
      </>
    ),
    shield: (
      <>
        <path d="M12 3.5 19 6v5.2c0 4.2-2.8 7.7-7 9.3-4.2-1.6-7-5.1-7-9.3V6z" />
        <path d="m8.8 12 2.1 2.1 4.4-4.6" />
      </>
    ),
    trash: (
      <>
        <path d="M4 7h16" />
        <path d="M10 11v6" />
        <path d="M14 11v6" />
        <path d="M6.5 7 7.5 20h9L17.5 7" />
        <path d="M9 7l1-3h4l1 3" />
      </>
    ),
    userPlus: (
      <>
        <circle cx="9" cy="8" r="3.2" />
        <path d="M3.5 19c.6-3.6 2.5-5.4 5.5-5.4s4.9 1.8 5.5 5.4" />
        <path d="M18 8v6" />
        <path d="M15 11h6" />
      </>
    ),
    users: (
      <>
        <circle cx="9" cy="8" r="3" />
        <path d="M3.5 19c.6-3.2 2.5-5 5.5-5s4.9 1.8 5.5 5" />
        <path d="M16 8a2.7 2.7 0 0 1 0 5.2" />
        <path d="M17 14c2 .5 3.2 2.1 3.5 4.5" />
      </>
    ),
  };

  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {paths[name]}
    </svg>
  );
}

function formatEditDate(value: string) {
  if (!value) return 'Chưa chọn';
  const date = new Date(`${value}T00:00:00`);
  if (Number.isNaN(date.getTime())) return 'Chưa chọn';
  return new Intl.DateTimeFormat('vi-VN', {
    weekday: 'long',
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(date);
}

function formatDocumentDate(value?: string) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat('vi-VN', {
    hour: '2-digit',
    minute: '2-digit',
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(date);
}

function formatDocumentSize(size?: number) {
  if (!size) return 'Không có kích thước';
  if (size >= 1_000_000) return `${(size / 1_000_000).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(size / 1_000))} KB`;
}

function formatEditDuration(meetingDate: string, startTime: string, endTime: string) {
  if (!meetingDate || !startTime || !endTime) return 'Chưa đủ thời gian';
  const startDateTime = new Date(combineDateTime(meetingDate, startTime));
  const endDateTime = new Date(combineDateTime(meetingDate, endTime));
  const minutes = Math.round((endDateTime.getTime() - startDateTime.getTime()) / 60000);

  if (!Number.isFinite(minutes) || minutes <= 0) return 'Chưa hợp lệ';

  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  if (hours === 0) return `${remainingMinutes} phút`;
  if (remainingMinutes === 0) return `${hours} giờ`;
  return `${hours} giờ ${remainingMinutes} phút`;
}

function getInitials(name: string) {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '--';
  const initials =
    parts.length > 1 ? `${parts[0][0]}${parts[parts.length - 1][0]}` : name.slice(0, 2);
  return initials.toUpperCase();
}

function getDocumentName(document: ManagementMeetingDocument) {
  return document.originalFileName ?? document.fileName ?? document.name;
}

function getDocumentMime(document: ManagementMeetingDocument) {
  return document.mimeType ?? document.contentType ?? document.file?.type ?? '';
}

function getDocumentExtension(document: ManagementMeetingDocument) {
  const name = getDocumentName(document);
  const extension = name.includes('.') ? (name.split('.').pop()?.toLowerCase() ?? '') : '';
  if (extension) return extension.slice(0, 4);
  const mime = getDocumentMime(document);
  if (mime.includes('pdf')) return 'pdf';
  if (mime.includes('sheet') || mime.includes('excel')) return 'xls';
  if (mime.includes('presentation') || mime.includes('powerpoint')) return 'ppt';
  if (mime.includes('word') || mime.includes('document')) return 'doc';
  return 'file';
}

function getDocumentTone(document: ManagementMeetingDocument) {
  const extension = getDocumentExtension(document);
  if (extension === 'pdf') return 'pdf';
  if (['xls', 'xlsx', 'csv'].includes(extension)) return 'sheet';
  if (['ppt', 'pptx'].includes(extension)) return 'presentation';
  return 'document';
}

export function MeetingForm(props: MeetingFormProps) {
  const initialMeeting = props.initialMeeting;
  const initialValues = props.initialValues;
  const creationMode = initialMeeting?.creationMode ?? props.creationMode ?? 'SCHEDULED';
  const isPointMeeting = creationMode === 'POINT';
  const [pointNamesText, setPointNamesText] = useState(
    initialMeeting?.points?.map((point) => point.name).join('\n') ??
      initialValues?.pointNames?.join('\n') ??
      '',
  );
  const [title, setTitle] = useState(initialMeeting?.title ?? initialValues?.title ?? '');
  const [description, setDescription] = useState(
    initialMeeting?.description ?? initialValues?.description ?? '',
  );
  const [meetingDate, setMeetingDate] = useState(
    toDateInputValue(initialMeeting?.startTime ?? initialValues?.startTime ?? null),
  );
  const [startTime, setStartTime] = useState(
    toTimeInputValue(initialMeeting?.startTime ?? initialValues?.startTime ?? null),
  );
  const [endTime, setEndTime] = useState(
    toTimeInputValue(initialMeeting?.endTime ?? initialValues?.endTime ?? null),
  );
  const [accessType, setAccessType] = useState<ManagementMeetingAccessType>(
    isPointMeeting ? 'PUBLIC' : (initialMeeting?.accessType ?? 'PRIVATE'),
  );
  const [participants, setParticipants] = useState<ManagementMeetingParticipant[]>(
    initialMeeting?.participants ?? initialValues?.participants ?? [],
  );
  const [documents, setDocuments] = useState<ManagementMeetingDocument[]>(
    initialMeeting?.documents ?? initialValues?.documents ?? [],
  );
  const [editingParticipantUserId, setEditingParticipantUserId] = useState<string | null>(null);
  const [editingParticipantRole, setEditingParticipantRole] =
    useState<ManagementMeetingRole>('MEMBER');
  const [participantPickerOpen, setParticipantPickerOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FormFieldErrors>({});
  const participantPickerButtonRef = useRef<HTMLButtonElement>(null);
  const meetingDateInputRef = useRef<HTMLInputElement>(null);
  const startTimeInputRef = useRef<HTMLInputElement>(null);
  const endTimeInputRef = useRef<HTMLInputElement>(null);

  const editingParticipant = editingParticipantUserId
    ? (participants.find((participant) => participant.userId === editingParticipantUserId) ?? null)
    : null;
  const availableRoleOptions = getMeetingParticipantRoleOptions(
    accessType,
    props.mode,
    editingParticipant,
    initialMeeting?.participants,
  );

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);

    const nextFieldErrors: FormFieldErrors = {};
    if (!title.trim()) {
      nextFieldErrors.title = 'Vui lòng nhập tên cuộc họp.';
    }
    if (!meetingDate) {
      nextFieldErrors.meetingDate = 'Vui lòng chọn ngày họp.';
    }
    if (!startTime) {
      nextFieldErrors.startTime = 'Vui lòng chọn giờ bắt đầu.';
    }
    if (!endTime) {
      nextFieldErrors.endTime = 'Vui lòng chọn giờ kết thúc.';
    }
    const pointNames = parseMeetingPointNames(pointNamesText);
    if (isPointMeeting) {
      const pointError = validateMeetingPointNames(pointNames);
      if (pointError) nextFieldErrors.pointNames = pointError;
    }

    if (Object.keys(nextFieldErrors).length > 0) {
      setFieldErrors(nextFieldErrors);
      setError('Vui lòng nhập đầy đủ các trường bắt buộc.');
      return;
    }

    const participantError = isPointMeeting
      ? validatePointMeetingParticipantRoles(participants)
      : validateMeetingParticipants(
          accessType,
          participants,
          props.mode,
          initialMeeting?.participants,
        );
    if (participantError) {
      setFieldErrors({ participants: participantError });
      setError(participantError);
      return;
    }

    setFieldErrors({});

    const startDateTime = combineDateTime(meetingDate, startTime);
    const endDateTime = combineDateTime(meetingDate, endTime);

    if (new Date(endDateTime) <= new Date(startDateTime)) {
      setError('Giờ kết thúc phải lớn hơn giờ bắt đầu.');
      return;
    }

    await props.onSubmit({
      title: title.trim(),
      description: description.trim() || undefined,
      startTime: startDateTime,
      endTime: endDateTime,
      accessType,
      ...(isPointMeeting ? { creationMode: 'POINT' as const, pointNames } : {}),
      participants,
      documents,
      status: initialMeeting?.status ?? initialValues?.status,
    });
  }

  function closeParticipantPicker() {
    setParticipantPickerOpen(false);
    window.setTimeout(() => participantPickerButtonRef.current?.focus(), 0);
  }

  function handleParticipantPickerConfirm(selections: ParticipantPickerSelection[]) {
    const nextParticipants: ManagementMeetingParticipant[] = [];

    for (const { user, role } of selections) {
      const duplicated =
        participants.some(
          (participant) =>
            participant.userId === user.publicId ||
            participant.email.toLowerCase() === user.email.toLowerCase(),
        ) ||
        nextParticipants.some(
          (participant) =>
            participant.userId === user.publicId ||
            participant.email.toLowerCase() === user.email.toLowerCase(),
        );

      if (duplicated) {
        setError('Người dùng này đã có trong danh sách tham gia.');
        closeParticipantPicker();
        return;
      }

      nextParticipants.push({
        userId: user.publicId,
        name: user.fullName,
        email: user.email,
        role,
        status: 'INVITED',
      });
    }

    setParticipants((current) => [...current, ...nextParticipants]);
    closeParticipantPicker();
    setError(null);
    setFieldErrors((current) => ({ ...current, participants: undefined }));
  }

  function handleParticipantEdit(participant: ManagementMeetingParticipant) {
    setEditingParticipantUserId(participant.userId);
    setEditingParticipantRole(participant.role);
  }

  function handleParticipantRoleSave() {
    if (!editingParticipantUserId) {
      return;
    }

    const nextParticipants = participants.map((participant) =>
      participant.userId === editingParticipantUserId
        ? { ...participant, role: editingParticipantRole }
        : participant,
    );
    const roleError = isPointMeeting
      ? validatePointMeetingParticipantRoles(nextParticipants)
      : validateMeetingParticipantRoles(
          accessType,
          nextParticipants,
          props.mode,
          initialMeeting?.participants,
        );
    if (roleError) {
      setError(roleError);
      return;
    }

    setParticipants(nextParticipants);
    setEditingParticipantUserId(null);
    setError(null);
  }

  function handleParticipantRemove(userId: string) {
    setParticipants((current) => current.filter((item) => item.userId !== userId));
    if (editingParticipantUserId === userId) {
      setEditingParticipantUserId(null);
    }
  }

  function handleAccessTypeChange(nextAccessType: ManagementMeetingAccessType) {
    if (isPointMeeting) return;
    if (nextAccessType === accessType) {
      return;
    }

    setAccessType(nextAccessType);
    setParticipantPickerOpen(false);
    setEditingParticipantUserId(null);
    setEditingParticipantRole('MEMBER');
    setError(null);
  }

  if (props.mode === 'edit') {
    const summaryDate = formatEditDate(meetingDate);
    const duration = formatEditDuration(meetingDate, startTime, endTime);
    const hasValidDuration = meetingDate && startTime && endTime && duration !== 'Chưa hợp lệ';
    const persistedDocuments = documents.filter((document) => !document.file);
    const localDocuments = documents.filter((document) => document.file) as Array<
      ManagementMeetingDocument & { file: File }
    >;
    const checklistItems = [
      { label: 'Đã nhập tên cuộc họp', complete: Boolean(title.trim()) },
      { label: 'Đã chọn thời gian họp', complete: Boolean(meetingDate && startTime && endTime) },
      { label: 'Đã chọn loại phòng họp', complete: Boolean(accessType) },
      { label: 'Có người tham gia', complete: isPointMeeting || participants.length > 0 },
      { label: 'Thời lượng hợp lệ', complete: Boolean(hasValidDuration) },
    ];
    const readyToSave = checklistItems.every((item) => item.complete);

    return (
      <form className={editStyles.page} onSubmit={handleSubmit}>
        <section className={editStyles.hero} aria-labelledby="meeting-edit-title">
          <div className={editStyles.heroOverlay} />
          <div className={editStyles.heroMain}>
            <nav className={editStyles.breadcrumb} aria-label="Breadcrumb">
              <span>
                <HomeBrandLink />
              </span>
              <span>/</span>
              <span>Cuộc họp</span>
              <span>/</span>
              <span>Chỉnh sửa cuộc họp</span>
            </nav>
            <button type="button" className={editStyles.backAction} onClick={props.onCancel}>
              <EditIcon name="arrowLeft" />
              <span>Quay lại chi tiết cuộc họp</span>
            </button>
            <div className={editStyles.heroText}>
              <h1 id="meeting-edit-title">Chỉnh sửa cuộc họp</h1>
              <p>Cập nhật thông tin cuộc họp. Các thay đổi sẽ được lưu và áp dụng ngay.</p>
            </div>
          </div>
          <div className={editStyles.heroTagline}>
            <span>Kết nối tri thức</span>
            <span>Tạo giá trị mới</span>
            <i aria-hidden="true" />
          </div>
        </section>

        {error ? (
          <div className={editStyles.formError} role="alert" aria-live="polite">
            {error}
          </div>
        ) : null}

        <div className={editStyles.layout}>
          <main className={editStyles.leftColumn}>
            {isPointMeeting ? (
              <MeetingPointFields
                value={pointNamesText}
                error={fieldErrors.pointNames}
                disabled={props.submitting}
                readOnly={initialMeeting?.pointsEditable === false}
                onChange={(value) => {
                  setPointNamesText(value);
                  setFieldErrors((current) => ({ ...current, pointNames: undefined }));
                }}
              />
            ) : null}
            <section className={editStyles.card}>
              <div className={editStyles.cardHeader}>
                <span className={editStyles.iconTile}>
                  <EditIcon name="info" />
                </span>
                <div>
                  <h2>Thông tin chung</h2>
                  <p>Cập nhật thông tin cơ bản của cuộc họp.</p>
                </div>
              </div>

              <div className={editStyles.formGrid}>
                <label className={editStyles.field}>
                  <span className={editStyles.fieldLabel}>Tên cuộc họp *</span>
                  <input
                    className={fieldErrors.title ? editStyles.fieldInputError : undefined}
                    value={title}
                    onChange={(event) => {
                      setTitle(event.target.value);
                      if (fieldErrors.title) {
                        setFieldErrors((current) => ({ ...current, title: undefined }));
                      }
                    }}
                    placeholder="Nhập tên cuộc họp"
                    aria-invalid={fieldErrors.title ? 'true' : 'false'}
                  />
                  {fieldErrors.title ? (
                    <span className={editStyles.fieldError}>{fieldErrors.title}</span>
                  ) : null}
                </label>

                <label className={editStyles.field}>
                  <span className={editStyles.fieldLabel}>Loại phòng</span>
                  <span className={editStyles.selectWrap}>
                    <select
                      value={accessType}
                      disabled={isPointMeeting}
                      onChange={(event) =>
                        handleAccessTypeChange(event.target.value as ManagementMeetingAccessType)
                      }
                    >
                      <option value="PRIVATE">Riêng tư</option>
                      <option value="PUBLIC">Tự do</option>
                    </select>
                    <EditIcon name="chevronDown" />
                  </span>
                </label>

                <label className={`${editStyles.field} ${editStyles.fieldFull}`}>
                  <span className={editStyles.fieldLabel}>Mô tả cuộc họp</span>
                  <span className={editStyles.textareaWrap}>
                    <textarea
                      rows={4}
                      value={description}
                      onChange={(event) => setDescription(event.target.value)}
                      placeholder="Mô tả ngắn cho nội dung hoặc mục tiêu cuộc họp"
                    />
                    <span>{description.length}/500</span>
                  </span>
                </label>

                <label className={editStyles.field}>
                  <span className={editStyles.fieldLabel}>Ngày họp *</span>
                  <span className={editStyles.inputIconWrap}>
                    <EditIcon name="calendar" />
                    <input
                      ref={meetingDateInputRef}
                      type="date"
                      className={fieldErrors.meetingDate ? editStyles.fieldInputError : undefined}
                      value={meetingDate}
                      onChange={(event) => {
                        setMeetingDate(event.target.value);
                        if (fieldErrors.meetingDate) {
                          setFieldErrors((current) => ({ ...current, meetingDate: undefined }));
                        }
                      }}
                      onClick={() => openNativePicker(meetingDateInputRef.current)}
                      onKeyDown={preventDateTimeTyping}
                      onPaste={(event) => event.preventDefault()}
                      onDrop={(event) => event.preventDefault()}
                      inputMode="none"
                      aria-invalid={fieldErrors.meetingDate ? 'true' : 'false'}
                    />
                  </span>
                  {fieldErrors.meetingDate ? (
                    <span className={editStyles.fieldError}>{fieldErrors.meetingDate}</span>
                  ) : null}
                </label>

                <label className={editStyles.field}>
                  <span className={editStyles.fieldLabel}>Giờ bắt đầu *</span>
                  <span className={editStyles.inputIconWrap}>
                    <EditIcon name="clock" />
                    <input
                      ref={startTimeInputRef}
                      type="time"
                      className={fieldErrors.startTime ? editStyles.fieldInputError : undefined}
                      value={startTime}
                      onChange={(event) => {
                        setStartTime(event.target.value);
                        if (fieldErrors.startTime) {
                          setFieldErrors((current) => ({ ...current, startTime: undefined }));
                        }
                      }}
                      onClick={() => openNativePicker(startTimeInputRef.current)}
                      step={60}
                      aria-invalid={fieldErrors.startTime ? 'true' : 'false'}
                    />
                  </span>
                  {fieldErrors.startTime ? (
                    <span className={editStyles.fieldError}>{fieldErrors.startTime}</span>
                  ) : null}
                </label>

                <label className={editStyles.field}>
                  <span className={editStyles.fieldLabel}>Giờ kết thúc *</span>
                  <span className={editStyles.inputIconWrap}>
                    <EditIcon name="clock" />
                    <input
                      ref={endTimeInputRef}
                      type="time"
                      className={fieldErrors.endTime ? editStyles.fieldInputError : undefined}
                      value={endTime}
                      onChange={(event) => {
                        setEndTime(event.target.value);
                        if (fieldErrors.endTime) {
                          setFieldErrors((current) => ({ ...current, endTime: undefined }));
                        }
                      }}
                      onClick={() => openNativePicker(endTimeInputRef.current)}
                      step={60}
                      aria-invalid={fieldErrors.endTime ? 'true' : 'false'}
                    />
                  </span>
                  {fieldErrors.endTime ? (
                    <span className={editStyles.fieldError}>{fieldErrors.endTime}</span>
                  ) : null}
                </label>
              </div>
            </section>

            <section className={editStyles.card}>
              <div className={editStyles.cardHeader}>
                <span className={`${editStyles.iconTile} ${editStyles.iconPurple}`}>
                  <EditIcon name="list" />
                </span>
                <div>
                  <h2>Nội dung / Chương trình cuộc họp</h2>
                  <p>Nhập các nội dung dự kiến sẽ trao đổi trong cuộc họp.</p>
                </div>
              </div>

              <div className={editStyles.agendaTable}>
                <div className={editStyles.agendaHead}>
                  <span>#</span>
                  <span>Nội dung</span>
                  <span>Thời lượng</span>
                  <span />
                </div>
                {editAgendaItems.map((item, index) => (
                  <div key={item.title} className={editStyles.agendaRow}>
                    <span className={editStyles.agendaIndex}>{index + 1}</span>
                    <span className={editStyles.agendaTitle}>{item.title}</span>
                    <span className={editStyles.agendaDuration}>{item.duration}</span>
                    <button
                      type="button"
                      className={editStyles.iconButton}
                      aria-label={`Xóa ${item.title}`}
                    >
                      <EditIcon name="trash" />
                    </button>
                  </div>
                ))}
              </div>
              <button type="button" className={editStyles.addOutlineButton}>
                <EditIcon name="plus" />
                <span>Thêm nội dung</span>
              </button>
            </section>

            <section className={editStyles.card}>
              <div className={editStyles.cardHeaderWithAction}>
                <div className={editStyles.cardHeader}>
                  <span className={editStyles.iconTile}>
                    <EditIcon name="document" />
                  </span>
                  <div>
                    <h2>Tài liệu cuộc họp</h2>
                    <p>Quản lý tài liệu đính kèm phục vụ cuộc họp.</p>
                  </div>
                </div>
                <MeetingDocumentUpload
                  selectedFiles={localDocuments}
                  disabled={props.submitting}
                  variant="meeting-edit"
                  onLocalFilesChange={(nextLocalDocuments) =>
                    setDocuments([...persistedDocuments, ...nextLocalDocuments])
                  }
                />
              </div>

              {documents.length > 0 ? (
                <div className={editStyles.documentList}>
                  {documents.map((document) => {
                    const fileName = getDocumentName(document);
                    const uploadedMeta = document.file
                      ? 'Tài liệu mới'
                      : [
                          document.uploadedBy?.displayName,
                          formatDocumentDate(document.uploadedAt ?? document.createdAt),
                        ]
                          .filter(Boolean)
                          .join(' · ');
                    return (
                      <div key={document.id} className={editStyles.documentRow}>
                        <span
                          className={`${editStyles.fileTypeTile} ${editStyles[`fileType${getDocumentTone(document)}`]}`}
                        >
                          <EditIcon name="file" />
                          <span>{getDocumentExtension(document).toUpperCase()}</span>
                        </span>
                        <div className={editStyles.documentInfo}>
                          <strong title={fileName}>{fileName}</strong>
                          <small>
                            {formatDocumentSize(document.size ?? document.fileSize)}
                            {uploadedMeta ? ` · ${uploadedMeta}` : ''}
                          </small>
                        </div>
                        <button
                          type="button"
                          className={editStyles.trashButton}
                          aria-label={`Xóa ${fileName}`}
                          onClick={() =>
                            setDocuments((current) =>
                              current.filter((item) => item.id !== document.id),
                            )
                          }
                          disabled={props.submitting}
                        >
                          <EditIcon name="trash" />
                        </button>
                      </div>
                    );
                  })}
                </div>
              ) : (
                <p className={editStyles.emptyText}>Chưa có tài liệu nào được chọn.</p>
              )}
            </section>
          </main>

          <aside className={editStyles.rightColumn}>
            <section className={editStyles.card}>
              <div className={editStyles.cardHeader}>
                <span className={editStyles.iconTile}>
                  <EditIcon name="document" />
                </span>
                <div>
                  <h2>Tóm tắt cuộc họp</h2>
                  <p>Thông tin tóm tắt sau khi cập nhật.</p>
                </div>
              </div>
              <div className={editStyles.summaryList}>
                <div className={editStyles.summaryRow}>
                  <EditIcon name="file" />
                  <span>Tên cuộc họp</span>
                  <strong title={title}>{title || 'Chưa nhập'}</strong>
                </div>
                <div className={editStyles.summaryRow}>
                  <EditIcon name={accessType === 'PRIVATE' ? 'lock' : 'users'} />
                  <span>Loại phòng</span>
                  <strong className={editStyles.summaryBadge}>
                    {MANAGEMENT_MEETING_ACCESS_TYPE_LABELS[accessType]}
                  </strong>
                </div>
                <div className={editStyles.summaryRow}>
                  <EditIcon name="calendar" />
                  <span>Thời gian</span>
                  <strong>
                    {summaryDate}
                    <small>
                      {startTime || '--:--'} - {endTime || '--:--'} ({duration})
                    </small>
                  </strong>
                </div>
                <div className={editStyles.summaryRow}>
                  <EditIcon name="users" />
                  <span>Số người tham gia</span>
                  <strong>{participants.length} người</strong>
                </div>
                <div className={editStyles.summaryRow}>
                  <EditIcon name="document" />
                  <span>Tài liệu đính kèm</span>
                  <strong>{documents.length} tài liệu</strong>
                </div>
              </div>
            </section>

            <section className={editStyles.card}>
              <div className={editStyles.cardHeaderWithAction}>
                <div className={editStyles.cardHeader}>
                  <span className={editStyles.iconTile}>
                    <EditIcon name="users" />
                  </span>
                  <div>
                    <h2>Người tham gia ({participants.length})</h2>
                    <p>
                      {isPointMeeting
                        ? 'Chọn chủ tọa và thư ký duyệt phát biểu. Bỏ khỏi danh sách sẽ gỡ vai trò chỉ định, không ngắt kết nối người đang họp.'
                        : 'Thêm thành viên và quản lý vai trò người tham gia cuộc họp.'}
                    </p>
                  </div>
                </div>
                <button
                  ref={participantPickerButtonRef}
                  type="button"
                  className={editStyles.pickParticipantButton}
                  onClick={() => setParticipantPickerOpen(true)}
                  disabled={props.submitting}
                >
                  <EditIcon name="userPlus" />
                  <span>Chọn người tham gia</span>
                </button>
              </div>

              {editingParticipant ? (
                <div className={editStyles.roleEditor}>
                  <div>
                    <strong>{editingParticipant.name}</strong>
                    <small>{editingParticipant.email}</small>
                  </div>
                  <label className={editStyles.field}>
                    <span className={editStyles.fieldLabel}>Vai trò</span>
                    <span className={editStyles.selectWrap}>
                      <select
                        value={editingParticipantRole}
                        onChange={(event) =>
                          setEditingParticipantRole(event.target.value as ManagementMeetingRole)
                        }
                      >
                        {availableRoleOptions.map((role) => (
                          <option key={role} value={role}>
                            {MANAGEMENT_MEETING_ROLE_LABELS[role]}
                          </option>
                        ))}
                      </select>
                      <EditIcon name="chevronDown" />
                    </span>
                  </label>
                  <div className={editStyles.roleEditorActions}>
                    <button type="button" onClick={() => setEditingParticipantUserId(null)}>
                      Hủy
                    </button>
                    <button type="button" onClick={handleParticipantRoleSave}>
                      Lưu vai trò
                    </button>
                  </div>
                </div>
              ) : null}

              {fieldErrors.participants ? (
                <p className={editStyles.fieldError} role="alert">
                  {fieldErrors.participants}
                </p>
              ) : null}

              {participants.length > 0 ? (
                <div className={editStyles.participantList}>
                  {participants.map((participant, index) => (
                    <article key={participant.userId} className={editStyles.participantRow}>
                      <span
                        className={`${editStyles.avatar} ${editStyles[`avatarTone${index % 5}`]}`}
                      >
                        {getInitials(participant.name)}
                      </span>
                      <div className={editStyles.participantInfo}>
                        <strong title={participant.name}>{participant.name}</strong>
                        <small title={participant.email}>{participant.email}</small>
                      </div>
                      <span
                        className={`${editStyles.roleBadge} ${editStyles[`role${participant.role}`]}`}
                      >
                        {MANAGEMENT_MEETING_ROLE_LABELS[participant.role]}
                      </span>
                      <div className={editStyles.participantActions}>
                        <button
                          type="button"
                          className={editStyles.iconButton}
                          aria-label={`Sửa vai trò ${participant.name}`}
                          onClick={() => handleParticipantEdit(participant)}
                        >
                          <span aria-hidden="true">...</span>
                        </button>
                        <button
                          type="button"
                          className={editStyles.iconButton}
                          aria-label={`Xóa ${participant.name}`}
                          onClick={() => handleParticipantRemove(participant.userId)}
                        >
                          <EditIcon name="trash" />
                        </button>
                      </div>
                    </article>
                  ))}
                </div>
              ) : (
                <p className={editStyles.emptyText}>Chưa có người tham gia nào.</p>
              )}
            </section>

            <section className={editStyles.card}>
              <div className={editStyles.checkHeader}>
                <div className={editStyles.cardHeader}>
                  <span className={editStyles.iconTile}>
                    <EditIcon name="shield" />
                  </span>
                  <div>
                    <h2>Kiểm tra trước khi lưu</h2>
                    <p>Vui lòng kiểm tra các thông tin sau trước khi lưu thay đổi.</p>
                  </div>
                </div>
                <span
                  className={`${editStyles.readyBadge} ${readyToSave ? editStyles.readyBadgeActive : ''}`}
                >
                  <EditIcon name="check" />
                  <span>Sẵn sàng lưu thay đổi</span>
                </span>
              </div>
              <div className={editStyles.checklist}>
                {checklistItems.map((item) => (
                  <span key={item.label} className={item.complete ? editStyles.checkItemDone : ''}>
                    <EditIcon name="check" />
                    {item.label}
                  </span>
                ))}
              </div>
            </section>
          </aside>
        </div>

        <div className={editStyles.actionBar}>
          <div className={editStyles.actionBarRight}>
            <button
              type="button"
              className={editStyles.cancelButton}
              onClick={props.onCancel}
              disabled={props.submitting}
            >
              Hủy
            </button>
            {/* PRESENTATION_ONLY: no current meeting draft persistence flow */}
            <button type="button" className={editStyles.draftButton} aria-disabled="true">
              <EditIcon name="document" />
              <span>Lưu nháp</span>
            </button>
            <button type="submit" className={editStyles.submitButton} disabled={props.submitting}>
              <EditIcon name="check" />
              <span>{props.submitting ? 'Đang lưu...' : 'Lưu thay đổi'}</span>
            </button>
          </div>
        </div>

        <ParticipantPickerDialog
          open={participantPickerOpen}
          accessType={accessType}
          mode="edit"
          existingParticipants={participants}
          onCancel={closeParticipantPicker}
          onConfirm={handleParticipantPickerConfirm}
        />
      </form>
    );
  }

  return (
    <form className={`${styles.form} ${styles.creationTheme}`} onSubmit={handleSubmit}>
      <div className={styles.heroActions}>
        <button
          type="button"
          className={styles.secondaryButton}
          onClick={props.onCancel}
          disabled={props.submitting}
        >
          Hủy
        </button>
        <button type="submit" className={styles.primaryButton} disabled={props.submitting}>
          {props.submitting ? 'Đang lưu...' : 'Lưu'}
        </button>
      </div>

      {error ? (
        <div className={styles.formError} role="alert" aria-live="polite">
          {error}
        </div>
      ) : null}

      <div className={styles.formLayout}>
        <div className={styles.formMain}>
          {isPointMeeting ? (
            <MeetingPointFields
              value={pointNamesText}
              error={fieldErrors.pointNames}
              disabled={props.submitting}
              onChange={(value) => {
                setPointNamesText(value);
                setFieldErrors((current) => ({ ...current, pointNames: undefined }));
              }}
            />
          ) : null}
          <section className={styles.panel}>
            <div className={styles.sectionHeading}>
              <div>
                <h3>Thông tin chung</h3>
                <p>Thiết lập nội dung, mô tả và khung thời gian cuộc họp.</p>
              </div>
            </div>

            <div className={styles.formGrid}>
              <label className={styles.field}>
                <span className={styles.fieldLabel}>Tên cuộc họp *</span>
                <input
                  className={fieldErrors.title ? styles.fieldInputError : undefined}
                  value={title}
                  onChange={(event) => {
                    setTitle(event.target.value);
                    if (fieldErrors.title) {
                      setFieldErrors((current) => ({ ...current, title: undefined }));
                    }
                  }}
                  placeholder="Nhập tên cuộc họp"
                  aria-invalid={fieldErrors.title ? 'true' : 'false'}
                />
                {fieldErrors.title ? (
                  <span className={styles.fieldError}>{fieldErrors.title}</span>
                ) : null}
              </label>

              <label className={`${styles.field} ${styles.fieldFull}`}>
                <span className={styles.fieldLabel}>Mô tả</span>
                <textarea
                  rows={5}
                  value={description}
                  onChange={(event) => setDescription(event.target.value)}
                  placeholder="Mô tả ngắn cho nội dung hoặc mục tiêu cuộc họp"
                />
              </label>

              <div className={`${styles.dateTimeRow} ${styles.fieldFull}`}>
                <label className={styles.field}>
                  <span className={styles.fieldLabel}>Ngày họp *</span>
                  <input
                    ref={meetingDateInputRef}
                    type="date"
                    className={fieldErrors.meetingDate ? styles.fieldInputError : undefined}
                    value={meetingDate}
                    onChange={(event) => {
                      setMeetingDate(event.target.value);
                      if (fieldErrors.meetingDate) {
                        setFieldErrors((current) => ({ ...current, meetingDate: undefined }));
                      }
                    }}
                    onClick={() => openNativePicker(meetingDateInputRef.current)}
                    onKeyDown={preventDateTimeTyping}
                    onPaste={(event) => event.preventDefault()}
                    onDrop={(event) => event.preventDefault()}
                    inputMode="none"
                    aria-invalid={fieldErrors.meetingDate ? 'true' : 'false'}
                  />
                  {fieldErrors.meetingDate ? (
                    <span className={styles.fieldError}>{fieldErrors.meetingDate}</span>
                  ) : null}
                </label>

                <label className={styles.field}>
                  <span className={styles.fieldLabel}>Giờ bắt đầu *</span>
                  <input
                    ref={startTimeInputRef}
                    type="time"
                    className={fieldErrors.startTime ? styles.fieldInputError : undefined}
                    value={startTime}
                    onChange={(event) => {
                      setStartTime(event.target.value);
                      if (fieldErrors.startTime) {
                        setFieldErrors((current) => ({ ...current, startTime: undefined }));
                      }
                    }}
                    onClick={() => openNativePicker(startTimeInputRef.current)}
                    step={60}
                    aria-invalid={fieldErrors.startTime ? 'true' : 'false'}
                  />
                  {fieldErrors.startTime ? (
                    <span className={styles.fieldError}>{fieldErrors.startTime}</span>
                  ) : null}
                </label>

                <label className={styles.field}>
                  <span className={styles.fieldLabel}>Giờ kết thúc *</span>
                  <input
                    ref={endTimeInputRef}
                    type="time"
                    className={fieldErrors.endTime ? styles.fieldInputError : undefined}
                    value={endTime}
                    onChange={(event) => {
                      setEndTime(event.target.value);
                      if (fieldErrors.endTime) {
                        setFieldErrors((current) => ({ ...current, endTime: undefined }));
                      }
                    }}
                    onClick={() => openNativePicker(endTimeInputRef.current)}
                    step={60}
                    aria-invalid={fieldErrors.endTime ? 'true' : 'false'}
                  />
                  {fieldErrors.endTime ? (
                    <span className={styles.fieldError}>{fieldErrors.endTime}</span>
                  ) : null}
                </label>
              </div>
            </div>
          </section>
        </div>

        <div className={styles.formSide}>
          <section className={styles.panel}>
            <div className={styles.sectionHeading}>
              <div>
                <h3>Người tham gia</h3>
                <p>
                  {accessType === 'PRIVATE'
                    ? 'Chọn những tài khoản được phép tham gia phòng riêng tư.'
                    : isPointMeeting
                      ? 'Có thể chọn chủ tọa và thư ký để duyệt phát biểu. Người tham dự vào bằng liên kết và chọn điểm cầu.'
                      : 'Chọn chủ tọa, thư ký và thành viên. Người tham dự khác vẫn có thể vào bằng liên kết.'}
                </p>
              </div>
            </div>

            <div
              className={`${styles.participantPickerActions} ${participants.length === 0 ? styles.participantPickerActionsEmpty : ''}`}
            >
              {participants.length > 0 ? (
                <div>
                  <strong>{participants.length} người đã chọn</strong>
                  <p>Chọn nhiều người và thiết lập vai trò trong một lượt.</p>
                </div>
              ) : null}

              <button
                ref={participantPickerButtonRef}
                type="button"
                className={styles.secondaryButton}
                onClick={() => setParticipantPickerOpen(true)}
                disabled={props.submitting}
              >
                Chọn người tham gia
              </button>
            </div>

            {editingParticipant ? (
              <div className={styles.participantRoleEditor}>
                <div>
                  <strong>{editingParticipant.name}</strong>
                  <p className={styles.listMeta}>{editingParticipant.email}</p>
                  <p className={styles.metaText}>User ID: {editingParticipant.userId}</p>
                </div>

                <label className={styles.field}>
                  <span className={styles.fieldLabel}>Vai trò</span>
                  <select
                    value={editingParticipantRole}
                    onChange={(event) =>
                      setEditingParticipantRole(event.target.value as ManagementMeetingRole)
                    }
                  >
                    {availableRoleOptions.map((role) => (
                      <option key={role} value={role}>
                        {MANAGEMENT_MEETING_ROLE_LABELS[role]}
                      </option>
                    ))}
                  </select>
                </label>

                <div className={styles.inlineActionGroup}>
                  <button
                    type="button"
                    className={styles.secondaryButton}
                    onClick={() => setEditingParticipantUserId(null)}
                  >
                    Hủy
                  </button>
                  <button
                    type="button"
                    className={styles.primaryButton}
                    onClick={handleParticipantRoleSave}
                  >
                    Lưu vai trò
                  </button>
                </div>
              </div>
            ) : null}

            {fieldErrors.participants ? (
              <p className={styles.fieldError} role="alert">
                {fieldErrors.participants}
              </p>
            ) : null}

            {participants.length > 0 ? (
              <div className={styles.participantListBlock}>
                <p className={styles.accessTypeNotice}>Đã chọn {participants.length} người</p>

                <div className={styles.cardsStack}>
                  {participants.map((participant) => (
                    <article key={participant.userId} className={styles.participantCard}>
                      <div className={styles.participantCardHeader}>
                        <div>
                          <div className={styles.participantNameRole}>
                            <strong className={styles.detailValueStrong}>{participant.name}</strong>
                            <MeetingRoleBadge role={participant.role} />
                          </div>
                          <p className={styles.listMeta}>{participant.email}</p>
                        </div>
                      </div>
                      <div className={styles.participantCardFooter}>
                        <span className={styles.metaText}>
                          User ID: {participant.userId}
                          {editingParticipantUserId === participant.userId
                            ? ' • Đang chỉnh sửa'
                            : ''}
                        </span>
                        <div className={styles.inlineActionGroup}>
                          <button
                            type="button"
                            className={styles.textButton}
                            onClick={() => handleParticipantEdit(participant)}
                          >
                            Sửa
                          </button>
                          <button
                            type="button"
                            className={styles.textButtonDanger}
                            onClick={() => handleParticipantRemove(participant.userId)}
                          >
                            Xóa
                          </button>
                        </div>
                      </div>
                    </article>
                  ))}
                </div>
              </div>
            ) : null}
          </section>

          <section className={styles.panel}>
            <div className={styles.sectionHeading}>
              <div>
                <h3>Tài liệu cuộc họp</h3>
                <p>
                  Có thể đính kèm tài liệu để người tham gia xem trước hoặc sử dụng trong cuộc họp.
                </p>
              </div>
            </div>

            <MeetingDocumentUpload
              variant="meeting-create"
              selectedFiles={
                documents.filter((document) => document.file) as Array<
                  ManagementMeetingDocument & { file: File }
                >
              }
              disabled={props.submitting}
              onLocalFilesChange={setDocuments}
            />

            {documents.some((document) => !document.file) ? (
              <div className={styles.documentsStack}>
                {documents
                  .filter((document) => !document.file)
                  .map((document) => (
                    <div key={document.id} className={styles.documentItem}>
                      <div>
                        <strong className={styles.documentTitle}>{document.name}</strong>
                        <p className={styles.documentMeta}>
                          {document.size
                            ? `${Math.max(1, Math.round(document.size / 1_000))} KB`
                            : 'Không có kích thước'}
                        </p>
                      </div>
                      <button
                        type="button"
                        className={styles.textButtonDanger}
                        onClick={() =>
                          setDocuments((current) =>
                            current.filter((item) => item.id !== document.id),
                          )
                        }
                      >
                        Xóa
                      </button>
                    </div>
                  ))}
              </div>
            ) : null}
          </section>
        </div>
      </div>

      <ParticipantPickerDialog
        className={styles.participantCreationTheme}
        open={participantPickerOpen}
        accessType={accessType}
        existingParticipants={participants}
        onCancel={closeParticipantPicker}
        onConfirm={handleParticipantPickerConfirm}
      />
    </form>
  );
}
