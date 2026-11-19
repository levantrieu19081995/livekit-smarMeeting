'use client';

import { HomeBrandLink } from '@/components/home/HomeBrandLink';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { type ReactNode, useCallback, useEffect, useState } from 'react';
import toast from 'react-hot-toast';
import { MeetingDocumentsPanel } from '@/components/management/documents/MeetingDocumentsPanel';
import { MeetingPollPanel } from '@/components/management/polls/MeetingPollPanel';
import { MeetingQnaPanel } from '@/components/management/qna/MeetingQnaPanel';
import { ActionMenu } from '@/components/meetings/ActionMenu';
import { DeleteMeetingDialog } from '@/components/meetings/DeleteMeetingDialog';
import { MeetingForm, type MeetingFormValues } from '@/components/meetings/MeetingForm';
import { QuickMeetingForm } from '@/components/meetings/QuickMeetingForm';
import {
  deleteManagementMeeting,
  getManagementMeetingById,
  updateManagementMeeting,
  MeetingPartialSaveError,
} from '@/services/management/managementMeetingApi';
import {
  MANAGEMENT_MEETING_ACCESS_TYPE_LABELS,
  MANAGEMENT_MEETING_ROLE_LABELS,
  MANAGEMENT_MEETING_STATUS_LABELS,
  MANAGEMENT_PARTICIPANT_STATUS_LABELS,
} from '@/types/management/meeting';
import type {
  ManagementMeeting,
  ManagementMeetingParticipant,
  ManagementMeetingRole,
  ManagementMeetingStatus,
  ManagementParticipantStatus,
} from '@/types/management/meeting';
import styles from '@/styles/management/ManagementMeetingDetail.module.css';

type LineIconName =
  | 'arrowLeft'
  | 'calendar'
  | 'check'
  | 'clock'
  | 'copy'
  | 'document'
  | 'edit'
  | 'info'
  | 'link'
  | 'live'
  | 'lock'
  | 'room'
  | 'user'
  | 'users'
  | 'video'
  | 'x';

function LineIcon({ name }: { name: LineIconName }) {
  const iconPaths: Record<LineIconName, ReactNode> = {
    arrowLeft: <path d="M19 12H5m0 0 6-6m-6 6 6 6" />,
    calendar: (
      <>
        <path d="M7 3v3" />
        <path d="M17 3v3" />
        <rect x="3.5" y="5" width="17" height="16" rx="2.5" />
        <path d="M3.5 9.5h17" />
      </>
    ),
    check: (
      <>
        <circle cx="12" cy="12" r="8" />
        <path d="m8.5 12 2.2 2.2 4.8-5" />
      </>
    ),
    clock: (
      <>
        <circle cx="12" cy="12" r="8" />
        <path d="M12 8v4l3 2" />
      </>
    ),
    copy: (
      <>
        <rect x="8" y="8" width="11" height="11" rx="2" />
        <path d="M5 15H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v1" />
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
    edit: (
      <>
        <path d="M4 17.5V20h2.5L17.8 8.7l-2.5-2.5L4 17.5Z" />
        <path d="m14.5 7 2.5 2.5" />
      </>
    ),
    info: (
      <>
        <circle cx="12" cy="12" r="8.5" />
        <path d="M12 11v5" />
        <path d="M12 8h.01" />
      </>
    ),
    link: (
      <>
        <path d="M10 13a5 5 0 0 0 7.1 0l2-2a5 5 0 0 0-7.1-7.1l-1.1 1.1" />
        <path d="M14 11a5 5 0 0 0-7.1 0l-2 2A5 5 0 0 0 12 20.1l1.1-1.1" />
      </>
    ),
    live: (
      <>
        <circle cx="12" cy="12" r="2.5" />
        <path d="M7.8 8.2a6 6 0 0 0 0 7.6" />
        <path d="M16.2 8.2a6 6 0 0 1 0 7.6" />
      </>
    ),
    lock: (
      <>
        <rect x="5" y="10" width="14" height="10" rx="2" />
        <path d="M8 10V7a4 4 0 0 1 8 0v3" />
      </>
    ),
    room: (
      <>
        <path d="M4 20V6l8-3 8 3v14" />
        <path d="M9 20v-7h6v7" />
        <path d="M8 9h.01" />
        <path d="M16 9h.01" />
      </>
    ),
    user: (
      <>
        <circle cx="12" cy="8" r="3.5" />
        <path d="M5 20c.8-4 3.2-6 7-6s6.2 2 7 6" />
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
    video: (
      <>
        <rect x="3.5" y="6.5" width="12" height="11" rx="2" />
        <path d="m15.5 10 5-2.8v9.6l-5-2.8" />
      </>
    ),
    x: (
      <>
        <circle cx="12" cy="12" r="8" />
        <path d="m9 9 6 6" />
        <path d="m15 9-6 6" />
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
      {iconPaths[name]}
    </svg>
  );
}

function capitalizeFirst(value: string) {
  return value ? value.charAt(0).toUpperCase() + value.slice(1) : value;
}

function formatMeetingDate(value: string | null) {
  if (!value) return 'Chưa bắt đầu';
  const formatted = new Intl.DateTimeFormat('vi-VN', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(new Date(value));

  return capitalizeFirst(formatted);
}

function formatStatDate(value: string | null) {
  if (!value) return '—';
  const date = new Date(value);
  const day = String(date.getDate()).padStart(2, '0');
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const year = date.getFullYear();

  return `${day}/${month}/${year}`;
}

function formatWeekday(value: string | null) {
  if (!value) return 'Không đặt lịch';
  return capitalizeFirst(
    new Intl.DateTimeFormat('vi-VN', {
      weekday: 'long',
    }).format(new Date(value)),
  );
}

function formatTime(value: string | null) {
  if (!value) return '—';
  return new Intl.DateTimeFormat('vi-VN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value));
}

function formatDuration(startTime: string | null, endTime: string | null) {
  if (!startTime) return 'Chưa bắt đầu';
  if (!endTime) return 'Không đặt giờ kết thúc';
  const minutes = Math.max(
    0,
    Math.round((new Date(endTime).getTime() - new Date(startTime).getTime()) / 60000),
  );
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

function getAvatarTone(name: string, index = 0) {
  const seed = Array.from(name).reduce((sum, character) => sum + character.charCodeAt(0), index);
  return seed % 5;
}

function shiftedTime(value: string | null, minutes: number) {
  if (!value) return '—';
  return formatTime(new Date(new Date(value).getTime() - minutes * 60000).toISOString());
}

function buildRecordedActivity(meeting: ManagementMeeting) {
  return meeting.participants
    .flatMap((participant) => [
      ...(participant.joinedAt
        ? [
            {
              at: participant.joinedAt,
              title: `${participant.name} đã tham gia cuộc họp`,
              tone: 'success',
            },
          ]
        : []),
      ...(participant.leftAt
        ? [
            {
              at: participant.leftAt,
              title: `${participant.name} đã rời cuộc họp`,
              tone: 'primary',
            },
          ]
        : []),
    ])
    .filter((event) => !Number.isNaN(new Date(event.at).getTime()))
    .sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime())
    .slice(0, 10)
    .map((event) => ({ ...event, time: formatTime(event.at) }));
}
function StatusBadge({ status }: { status: ManagementMeetingStatus }) {
  const statusIcon: Record<ManagementMeetingStatus, LineIconName> = {
    UPCOMING: 'clock',
    IN_PROGRESS: 'live',
    COMPLETED: 'check',
    CANCELLED: 'x',
  };

  return (
    <span className={`${styles.statusBadge} ${styles[`status${status}`]}`}>
      <LineIcon name={statusIcon[status]} />
      <span>{MANAGEMENT_MEETING_STATUS_LABELS[status]}</span>
    </span>
  );
}

function RoleBadge({ role }: { role: ManagementMeetingRole }) {
  return (
    <span className={`${styles.roleBadge} ${styles[`role${role}`]}`}>
      {MANAGEMENT_MEETING_ROLE_LABELS[role]}
    </span>
  );
}

function PersonSummary({
  participant,
  extra,
}: {
  participant?: ManagementMeetingParticipant;
  extra?: string;
}) {
  if (!participant) {
    return (
      <div className={styles.personSummary}>
        <strong>Chưa chỉ định</strong>
      </div>
    );
  }

  return (
    <div className={styles.personSummary}>
      <span
        className={`${styles.personAvatar} ${styles[`avatarTone${getAvatarTone(participant.name)}`]}`}
      >
        {getInitials(participant.name)}
      </span>
      <div>
        <strong>{participant.name}</strong>
        <small>{participant.email}</small>
        {extra ? <small>{extra}</small> : null}
      </div>
    </div>
  );
}

interface MeetingDetailPageProps {
  meetingId: string;
  initialMode?: string;
}

export function MeetingDetailPage(props: MeetingDetailPageProps) {
  const router = useRouter();
  const [meeting, setMeeting] = useState<ManagementMeeting | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const isEditMode = props.initialMode === 'edit';

  const loadMeeting = useCallback(async () => {
    try {
      setLoading(true);
      const foundMeeting = await getManagementMeetingById(props.meetingId);
      setMeeting(foundMeeting);
      setError(null);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : 'Không thể tải chi tiết cuộc họp.');
      setMeeting(null);
    } finally {
      setLoading(false);
    }
  }, [props.meetingId]);

  useEffect(() => {
    void loadMeeting();
  }, [loadMeeting]);

  async function handleUpdate(values: MeetingFormValues) {
    if (!meeting?.canManage) {
      return;
    }

    try {
      setSubmitting(true);
      const updatedMeeting = await updateManagementMeeting(
        meeting.id,
        values,
        meeting.participants,
        meeting.documents,
      );
      setMeeting(updatedMeeting);
      toast.success('Đã cập nhật cuộc họp.');
      router.push(`/management/meetings/${meeting.id}`);
    } catch (updateError) {
      if (updateError instanceof MeetingPartialSaveError) {
        if (updateError.savedMeeting) setMeeting(updateError.savedMeeting);
        router.push(`/management/meetings/${meeting.id}`);
        void loadMeeting();
      }
      toast.error(
        updateError instanceof Error ? updateError.message : 'Không thể cập nhật cuộc họp.',
      );
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDelete() {
    if (!meeting?.canManage) {
      return;
    }

    try {
      setSubmitting(true);
      const cleanupPending = await deleteManagementMeeting(meeting.id);
      toast.success(
        cleanupPending
          ? 'Đã xóa dữ liệu cuộc họp; hệ thống đang đóng các kết nối còn lại.'
          : 'Đã xóa cuộc họp.',
      );
      router.push('/management/meetings');
    } catch (deleteError) {
      toast.error(deleteError instanceof Error ? deleteError.message : 'Không thể xóa cuộc họp.');
    } finally {
      setSubmitting(false);
    }
  }

  async function handleCopyLink() {
    if (!meeting) {
      return;
    }

    await navigator.clipboard.writeText(`${window.location.origin}/join/${meeting.id}`);
    toast.success('Đã sao chép link cuộc họp.');
  }

  if (loading) {
    return <div className={styles.stateCard}>Đang tải chi tiết cuộc họp...</div>;
  }

  if (error || !meeting) {
    return (
      <div className={styles.stateCard}>
        <p>{error ?? 'Không tìm thấy cuộc họp.'}</p>
        <Link href="/management/meetings" className={styles.primaryAction}>
          Quay lại danh sách
        </Link>
      </div>
    );
  }

  if (isEditMode && meeting.canManage) {
    if (meeting.creationMode === 'INSTANT' || meeting.creationMode === 'LATER') {
      return (
        <QuickMeetingForm
          meeting={meeting}
          submitting={submitting}
          onSubmit={handleUpdate}
          onCancel={() => router.push(`/management/meetings/${meeting.id}`)}
        />
      );
    }
    return (
      <MeetingForm
        mode="edit"
        initialMeeting={meeting}
        submitting={submitting}
        onSubmit={handleUpdate}
        onCancel={() => router.push(`/management/meetings/${meeting.id}`)}
      />
    );
  }

  const meetingLink = `/join/${meeting.id}`;
  const timeRange = `${formatTime(meeting.startTime)} - ${formatTime(meeting.endTime)}`;
  const duration = formatDuration(meeting.startTime, meeting.endTime);
  const chairman = meeting.participants.find((participant) => participant.role === 'CHAIRMAN');
  const secretaries = meeting.participants.filter(
    (participant) => participant.role === 'SECRETARY',
  );
  const firstSecretary = secretaries[0];
  const recordedActivity = buildRecordedActivity(meeting);

  return (
    <div className={styles.page}>
      <header className={styles.detailHeader}>
        <div className={styles.heroOverlay} />
        <nav className={styles.breadcrumb} aria-label="Breadcrumb">
          <HomeBrandLink className={styles.breadcrumbLink} />
          <span className={styles.breadcrumbDivider}>/</span>
          <Link href="/management/meetings" className={styles.breadcrumbLink}>
            Cuộc họp
          </Link>
          <span className={styles.breadcrumbDivider}>/</span>
          <span>Chi tiết</span>
        </nav>

        <div className={styles.headerTop}>
          <div className={styles.detailHeaderMain}>
            <div className={styles.titleRow}>
              <Link
                href="/management/meetings"
                className={styles.backButton}
                aria-label="Quay lại danh sách"
              >
                <LineIcon name="arrowLeft" />
              </Link>
              <h1 className={styles.title}>{meeting.title}</h1>
              <StatusBadge status={meeting.status} />
            </div>

            <div className={styles.headerMeta}>
              <span className={styles.metaItem}>
                Mã cuộc họp: <strong>{meeting.meetingCode ?? meeting.roomName}</strong>
              </span>
              <span className={styles.metaDivider} />
              <span className={styles.metaItem}>
                <LineIcon name="calendar" />
                {formatMeetingDate(meeting.startTime)}
              </span>
              <span className={styles.metaDivider} />
              <span className={styles.metaItem}>
                <LineIcon name="clock" />
                {timeRange} ({duration})
              </span>
              <span className={styles.metaDivider} />
              <span className={styles.metaItem}>
                <LineIcon name="users" />
                {meeting.participants.length} người tham gia
              </span>
            </div>
          </div>

          <div className={styles.headerActions}>
            <button
              type="button"
              className={styles.primaryAction}
              onClick={() => router.push(`/join/${meeting.id}`)}
            >
              <LineIcon name="video" />
              <span>Vào họp</span>
            </button>
            {meeting.canManage ? (
              <Link
                href={`/management/meetings/${meeting.id}?mode=edit`}
                className={styles.secondaryAction}
              >
                <LineIcon name="edit" />
                <span>Chỉnh sửa</span>
              </Link>
            ) : null}
            <button
              type="button"
              className={styles.secondaryAction}
              onClick={() => void handleCopyLink()}
            >
              <LineIcon name="link" />
              <span>Sao chép liên kết</span>
            </button>
            {meeting.creationMode === 'POINT' ? (
              <Link
                href={`/management/meetings/${meeting.id}/speaking`}
                className={styles.secondaryAction}
              >
                <LineIcon name="document" />
                <span>Lịch sử phát biểu</span>
              </Link>
            ) : null}
            {meeting.canManage ? (
              <ActionMenu
                label={`Tác vụ cho cuộc họp ${meeting.title}`}
                items={[
                  {
                    label: 'Xóa',
                    icon: 'delete',
                    danger: true,
                    onSelect: () => setConfirmDelete(true),
                  },
                ]}
              />
            ) : null}
          </div>
        </div>
        <div className={styles.heroTagline}>
          <span>Kết nối tri thức</span>
          <span>Tạo giá trị mới</span>
          <i aria-hidden="true" />
        </div>
      </header>

      <div className={styles.mainGrid}>
        <main className={styles.leftColumn}>
          <section className={styles.card}>
            <div className={styles.cardHeading}>
              <span className={`${styles.cardHeadingIcon} ${styles.iconBlue}`}>
                <LineIcon name="document" />
              </span>
              <h2 className={styles.cardTitle}>Tổng quan cuộc họp</h2>
            </div>
            <p className={styles.overviewDescription}>
              {meeting.description ||
                'Chưa có mô tả cho cuộc họp này. Bạn có thể bổ sung thêm nội dung quản trị khi cần.'}
            </p>

            <div className={styles.statGrid}>
              <article className={styles.statCard}>
                <span className={`${styles.statIcon} ${styles.iconBlue}`}>
                  <LineIcon name="calendar" />
                </span>
                <span className={styles.statLabel}>Ngày họp</span>
                <strong className={styles.statValue}>{formatStatDate(meeting.startTime)}</strong>
                <span className={styles.statMeta}>{formatWeekday(meeting.startTime)}</span>
              </article>
              <article className={styles.statCard}>
                <span className={`${styles.statIcon} ${styles.iconPurple}`}>
                  <LineIcon name="clock" />
                </span>
                <span className={styles.statLabel}>Thời gian</span>
                <strong className={styles.statValue}>{timeRange}</strong>
                <span className={styles.statMeta}>({duration})</span>
              </article>
              <article className={styles.statCard}>
                <span className={`${styles.statIcon} ${styles.iconBlue}`}>
                  <LineIcon name="users" />
                </span>
                <span className={styles.statLabel}>Số người tham gia</span>
                <strong className={styles.statValue}>{meeting.participants.length} người</strong>
                <span className={styles.statMeta}>Trong danh sách</span>
              </article>
              <article className={styles.statCard}>
                <span className={`${styles.statIcon} ${styles.iconGreen}`}>
                  <LineIcon
                    name={
                      meeting.status === 'IN_PROGRESS'
                        ? 'live'
                        : meeting.status === 'COMPLETED'
                          ? 'check'
                          : meeting.status === 'CANCELLED'
                            ? 'x'
                            : 'video'
                    }
                  />
                </span>
                <span className={styles.statLabel}>Trạng thái</span>
                <strong className={styles.statValue}>
                  {MANAGEMENT_MEETING_STATUS_LABELS[meeting.status]}
                </strong>
                {meeting.status === 'IN_PROGRESS' ? (
                  <span className={styles.statMeta}>Theo lịch {formatTime(meeting.startTime)}</span>
                ) : null}
              </article>
            </div>
          </section>

          <section className={styles.card}>
            <div className={styles.cardHeading}>
              <span className={`${styles.cardHeadingIcon} ${styles.iconPurple}`}>
                <LineIcon name="document" />
              </span>
              <h2 className={styles.cardTitle}>Nội dung / Chương trình họp</h2>
            </div>
            <div className={styles.agendaList}>
              {!meeting.description ? (
                <p className={styles.emptySection}>Chưa có chương trình họp được nhập.</p>
              ) : null}
              {(meeting.description ? [{ title: meeting.description, duration: '' }] : []).map(
                (item, index) => (
                  <div key={item.title} className={styles.agendaItem}>
                    <span className={styles.agendaNumber}>{index + 1}</span>
                    <strong>{item.title}</strong>
                    <span>{item.duration}</span>
                  </div>
                ),
              )}
            </div>
          </section>

          <section className={styles.card}>
            <MeetingPollPanel key={meeting.id} meetingId={String(meeting.id)} />
          </section>

          <section className={styles.card}>
            <MeetingQnaPanel key={meeting.id} meetingId={String(meeting.id)} />
          </section>

          <div className={styles.bottomGrid}>
            <section className={`${styles.card} ${styles.documentCard}`}>
              <MeetingDocumentsPanel
                meetingId={meeting.id}
                title="Tài liệu đính kèm"
                variant="detail"
              />
            </section>

            <section className={`${styles.card} ${styles.activityCard}`}>
              <div className={styles.cardHeading}>
                <span className={`${styles.cardHeadingIcon} ${styles.iconBlue}`}>
                  <LineIcon name="clock" />
                </span>
                <h2 className={styles.cardTitle}>Hoạt động gần đây</h2>
              </div>
              <div className={styles.activityTimeline}>
                {recordedActivity.length === 0 ? (
                  <p className={styles.emptySection}>Chưa có hoạt động được ghi nhận.</p>
                ) : null}
                {recordedActivity.map((activity) => (
                  <div key={`${activity.time}-${activity.title}`} className={styles.activityItem}>
                    <time>{activity.time}</time>
                    <span
                      className={`${styles.activityDot} ${styles[`activityDot${activity.tone}`]}`}
                    />
                    <div>
                      <strong>{activity.title}</strong>
                    </div>
                  </div>
                ))}
              </div>
            </section>
          </div>
        </main>

        <aside className={styles.rightColumn}>
          <section className={styles.card}>
            <div className={styles.cardHeading}>
              <span className={`${styles.cardHeadingIcon} ${styles.iconBlue}`}>
                <LineIcon name="info" />
              </span>
              <h2 className={styles.cardTitle}>Thông tin cuộc họp</h2>
            </div>

            <div className={styles.infoList}>
              <div className={styles.infoRow}>
                <div className={styles.infoRowLabel}>
                  <LineIcon name="user" />
                  <span>Chủ tọa</span>
                </div>
                <PersonSummary participant={chairman} />
              </div>
              <div className={styles.infoRow}>
                <div className={styles.infoRowLabel}>
                  <LineIcon name="user" />
                  <span>Thư ký</span>
                </div>
                <PersonSummary
                  participant={firstSecretary}
                  extra={secretaries.length > 1 ? `+${secretaries.length - 1} thư ký` : undefined}
                />
              </div>
              <div className={styles.infoRow}>
                <div className={styles.infoRowLabel}>
                  <LineIcon name={meeting.accessType === 'PRIVATE' ? 'lock' : 'room'} />
                  <span>Loại phòng</span>
                </div>
                <div className={styles.infoText}>
                  <strong>
                    {meeting.creationMode === 'POINT'
                      ? 'Điểm cầu'
                      : MANAGEMENT_MEETING_ACCESS_TYPE_LABELS[meeting.accessType]}
                  </strong>
                  <small>Phòng: {meeting.roomName}</small>
                </div>
              </div>
              <div className={styles.infoRow}>
                <div className={styles.infoRowLabel}>
                  <LineIcon name="video" />
                  <span>Trạng thái</span>
                </div>
                <div className={styles.infoText}>
                  <StatusBadge status={meeting.status} />
                </div>
              </div>
              <div className={styles.infoRow}>
                <div className={styles.infoRowLabel}>
                  <LineIcon name="room" />
                  <span>Room SID</span>
                </div>
                <div className={styles.infoText}>
                  <strong>{meeting.roomSid ?? 'Chưa có'}</strong>
                </div>
              </div>
              <div className={styles.infoRow}>
                <div className={styles.infoRowLabel}>
                  <LineIcon name="link" />
                  <span>Liên kết tham gia</span>
                </div>
                <div className={styles.infoText}>
                  <span className={styles.copyValue}>
                    <strong>{meetingLink}</strong>
                    <button
                      type="button"
                      className={styles.copyButton}
                      onClick={() => void handleCopyLink()}
                    >
                      <LineIcon name="copy" />
                      <span className={styles.visuallyHidden}>Sao chép liên kết</span>
                    </button>
                  </span>
                </div>
              </div>
            </div>
          </section>

          <section className={styles.card}>
            <div className={styles.cardHeading}>
              <span className={`${styles.cardHeadingIcon} ${styles.iconBlue}`}>
                <LineIcon name="users" />
              </span>
              <h2 className={styles.cardTitle}>
                Danh sách người tham gia ({meeting.participants.length})
              </h2>
            </div>

            {meeting.participants.length > 0 ? (
              <div className={styles.participantList}>
                {meeting.participants.map((participant, index) => {
                  const joinStatus = participant.status ?? 'INVITED';
                  return (
                    <article key={participant.userId} className={styles.participantRow}>
                      <span className={styles.participantAvatarWrap}>
                        <span
                          className={`${styles.participantAvatar} ${styles[`avatarTone${getAvatarTone(participant.name, index)}`]}`}
                        >
                          {getInitials(participant.name)}
                        </span>
                        <span
                          className={`${styles.participantStatusDot} ${styles[`participantStatus${joinStatus}`]}`}
                          aria-label={MANAGEMENT_PARTICIPANT_STATUS_LABELS[joinStatus]}
                          title={MANAGEMENT_PARTICIPANT_STATUS_LABELS[joinStatus]}
                        />
                      </span>
                      <div className={styles.participantIdentity}>
                        <strong className={styles.participantName}>{participant.name}</strong>
                        <span className={styles.participantEmail}>{participant.email}</span>
                      </div>
                      <RoleBadge role={participant.role} />
                    </article>
                  );
                })}
              </div>
            ) : (
              <p className={styles.emptySection}>Chưa có người tham gia nào.</p>
            )}
          </section>
        </aside>
      </div>

      {confirmDelete ? (
        <DeleteMeetingDialog
          meetingTitle={meeting.title}
          isDeleting={submitting}
          onCancel={() => setConfirmDelete(false)}
          onConfirm={() => void handleDelete()}
        />
      ) : null}
    </div>
  );
}
