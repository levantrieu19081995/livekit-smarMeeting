'use client';

import { HomeBrandLink } from '@/components/home/HomeBrandLink';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react';
import toast from 'react-hot-toast';
import { ManagementPageHero } from '@/components/management/ManagementPageHero';
import { ActionMenu } from '@/components/meetings/ActionMenu';
import { DeleteMeetingDialog } from '@/components/meetings/DeleteMeetingDialog';
import { MeetingCreateMenu } from '@/components/meetings/MeetingCreateMenu';
import {
  deleteManagementMeeting,
  listManagementMeetings,
} from '@/services/management/managementMeetingApi';
import {
  MANAGEMENT_MEETING_ACCESS_TYPE_LABELS,
  MANAGEMENT_MEETING_STATUS_LABELS,
} from '@/types/management/meeting';
import type {
  ManagementMeeting,
  ManagementMeetingAccessType,
  ManagementMeetingStatus,
} from '@/types/management/meeting';
import styles from '@/styles/management/ManagementMeetingsList.module.css';

type LineIconName =
  | 'search'
  | 'calendar'
  | 'chevron'
  | 'list'
  | 'video'
  | 'document'
  | 'users'
  | 'lock'
  | 'clock'
  | 'live'
  | 'check'
  | 'x'
  | 'plus';

function LineIcon({ name }: { name: LineIconName }) {
  const iconPaths: Record<LineIconName, ReactNode> = {
    search: (
      <>
        <circle cx="11" cy="11" r="6.5" />
        <path d="m16 16 4 4" />
      </>
    ),
    calendar: (
      <>
        <path d="M7 3v3" />
        <path d="M17 3v3" />
        <rect x="3.5" y="5" width="17" height="16" rx="2.5" />
        <path d="M3.5 9.5h17" />
      </>
    ),
    chevron: <path d="m7 10 5 5 5-5" />,
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
    video: (
      <>
        <rect x="3.5" y="6.5" width="12" height="11" rx="2" />
        <path d="m15.5 10 5-2.8v9.6l-5-2.8" />
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
    users: (
      <>
        <circle cx="9" cy="8" r="3" />
        <path d="M3.5 19c.6-3.2 2.5-5 5.5-5s4.9 1.8 5.5 5" />
        <path d="M16 8a2.7 2.7 0 0 1 0 5.2" />
        <path d="M17 14c2 .5 3.2 2.1 3.5 4.5" />
      </>
    ),
    lock: (
      <>
        <rect x="5" y="10" width="14" height="10" rx="2" />
        <path d="M8 10V7a4 4 0 0 1 8 0v3" />
      </>
    ),
    clock: (
      <>
        <circle cx="12" cy="12" r="8" />
        <path d="M12 8v4l3 2" />
      </>
    ),
    live: (
      <>
        <circle cx="12" cy="12" r="2.5" />
        <path d="M7.8 8.2a6 6 0 0 0 0 7.6" />
        <path d="M16.2 8.2a6 6 0 0 1 0 7.6" />
      </>
    ),
    check: (
      <>
        <circle cx="12" cy="12" r="8" />
        <path d="m8.5 12 2.2 2.2 4.8-5" />
      </>
    ),
    x: (
      <>
        <circle cx="12" cy="12" r="8" />
        <path d="m9 9 6 6" />
        <path d="m15 9-6 6" />
      </>
    ),
    plus: (
      <>
        <path d="M12 5v14" />
        <path d="M5 12h14" />
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
  const date = new Date(value);
  const weekday = new Intl.DateTimeFormat('vi-VN', {
    weekday: 'long',
  }).format(date);
  const day = String(date.getDate()).padStart(2, '0');
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const year = date.getFullYear();

  return `${capitalizeFirst(weekday)}, ${day}/${month}/${year}`;
}

function formatMeetingTime(value: string | null) {
  if (!value) return '—';
  return new Intl.DateTimeFormat('vi-VN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value));
}

function formatMeetingDuration(startTime: string | null, endTime: string | null) {
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
  const initials =
    parts.length > 1 ? `${parts[0][0]}${parts[parts.length - 1][0]}` : name.slice(0, 2);

  return initials.toUpperCase();
}

function EmptyState({ message }: { message: string }) {
  return (
    <div className={styles.emptyState}>
      <div className={styles.emptyStateIcon} aria-hidden="true">
        <LineIcon name="calendar" />
      </div>
      <p>{message}</p>
    </div>
  );
}

function AccessBadge({
  accessType,
  isPointMeeting = false,
}: {
  accessType: ManagementMeetingAccessType;
  isPointMeeting?: boolean;
}) {
  const isPublic = accessType === 'PUBLIC';

  return (
    <span
      className={`${styles.accessBadge} ${isPublic ? styles.accessBadgePublic : styles.accessBadgePrivate}`}
    >
      <LineIcon name={isPublic ? 'users' : 'lock'} />
      <span>{isPointMeeting ? 'Điểm cầu' : MANAGEMENT_MEETING_ACCESS_TYPE_LABELS[accessType]}</span>
    </span>
  );
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

export function MeetingsListPage() {
  const router = useRouter();
  const [meetings, setMeetings] = useState<ManagementMeeting[]>([]);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | ManagementMeetingStatus>('ALL');
  const [accessFilter, setAccessFilter] = useState<'ALL' | ManagementMeetingAccessType>('ALL');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedMeeting, setSelectedMeeting] = useState<ManagementMeeting | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);

  const loadMeetings = useCallback(async () => {
    try {
      setLoading(true);
      setMeetings(
        await listManagementMeetings({
          search: search || undefined,
          status: statusFilter,
          accessType: accessFilter,
          size: 30,
        }),
      );
      setError(null);
    } catch (loadError) {
      setError(
        loadError instanceof Error ? loadError.message : 'Không thể tải danh sách cuộc họp.',
      );
    } finally {
      setLoading(false);
    }
  }, [accessFilter, search, statusFilter]);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => {
      void loadMeetings();
    }, 150);

    return () => window.clearTimeout(timeoutId);
  }, [loadMeetings]);

  const filteredMeetings = useMemo(() => meetings, [meetings]);

  async function handleCopyLink(meetingId: string) {
    const meetingLink = `${window.location.origin}/join/${meetingId}`;
    await navigator.clipboard.writeText(meetingLink);
    toast.success('Đã sao chép link cuộc họp.');
  }

  async function handleDeleteMeeting() {
    if (!selectedMeeting?.canManage) {
      return;
    }

    try {
      setIsDeleting(true);
      const cleanupPending = await deleteManagementMeeting(selectedMeeting.id);
      toast.success(
        cleanupPending
          ? 'Đã xóa dữ liệu cuộc họp; hệ thống đang đóng các kết nối còn lại.'
          : 'Đã xóa cuộc họp.',
      );
      setSelectedMeeting(null);
      await loadMeetings();
    } catch (deleteError) {
      toast.error(deleteError instanceof Error ? deleteError.message : 'Không thể xóa cuộc họp.');
    } finally {
      setIsDeleting(false);
    }
  }

  function renderActions(meeting: ManagementMeeting) {
    return (
      <ActionMenu
        label={`Tác vụ cho cuộc họp ${meeting.title}`}
        items={[
          {
            label: 'Xem chi tiết',
            icon: 'view',
            onSelect: () => router.push(`/management/meetings/${meeting.id}`),
          },
          {
            label: 'Vào họp',
            icon: 'join',
            onSelect: () => router.push(`/join/${meeting.id}`),
          },
          {
            label: 'Sao chép link',
            icon: 'copy',
            onSelect: () => void handleCopyLink(meeting.id),
          },
          ...(meeting.canManage
            ? [
                {
                  label: 'Chỉnh sửa',
                  icon: 'edit' as const,
                  onSelect: () => router.push(`/management/meetings/${meeting.id}?mode=edit`),
                },
                {
                  label: 'Xóa',
                  icon: 'delete' as const,
                  onSelect: () => setSelectedMeeting(meeting),
                  danger: true,
                },
              ]
            : []),
        ]}
      />
    );
  }

  return (
    <div className={styles.page}>
      <ManagementPageHero
        eyebrow={
          <span className={styles.heroBreadcrumb}>
            <span>
              <HomeBrandLink />
            </span>
            <span className={styles.heroBreadcrumbSlash}>/</span>
            <span className={styles.heroBreadcrumbMuted}>Cuộc họp</span>
          </span>
        }
        title="Danh sách cuộc họp"
        subtitle={
          <>
            <span className={styles.heroSubtitleLine}>
              Quản lý, theo dõi và tham gia các cuộc họp của CUSC.
            </span>
            <span className={styles.heroSubtitleLine}>
              Tạo cuộc họp mới, xem chi tiết và quản lý lịch họp một cách dễ dàng.
            </span>
          </>
        }
        actionsContent={<MeetingCreateMenu onCreated={() => void loadMeetings()} />}
        tagline={{ line1: 'Kết nối tri thức', line2: 'Tạo giá trị mới' }}
      />

      <section className={styles.toolbar} aria-label="Bộ lọc cuộc họp">
        <label className={styles.searchControl}>
          <span className={styles.visuallyHidden}>Tìm kiếm</span>
          <span className={styles.searchIcon} aria-hidden="true">
            <LineIcon name="search" />
          </span>
          <input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Tìm kiếm cuộc họp theo tên, mã hoặc người tạo..."
          />
        </label>

        <label className={styles.selectControl}>
          <span className={styles.visuallyHidden}>Trạng thái</span>
          <select
            value={statusFilter}
            onChange={(event) =>
              setStatusFilter(event.target.value as 'ALL' | ManagementMeetingStatus)
            }
          >
            <option value="ALL">Tất cả trạng thái</option>
            <option value="UPCOMING">{MANAGEMENT_MEETING_STATUS_LABELS.UPCOMING}</option>
            <option value="IN_PROGRESS">{MANAGEMENT_MEETING_STATUS_LABELS.IN_PROGRESS}</option>
            <option value="COMPLETED">{MANAGEMENT_MEETING_STATUS_LABELS.COMPLETED}</option>
            <option value="CANCELLED">{MANAGEMENT_MEETING_STATUS_LABELS.CANCELLED}</option>
          </select>
          <span aria-hidden="true">
            <LineIcon name="chevron" />
          </span>
        </label>

        <label className={styles.selectControl}>
          <span className={styles.visuallyHidden}>Loại phòng</span>
          <select
            value={accessFilter}
            onChange={(event) =>
              setAccessFilter(event.target.value as 'ALL' | ManagementMeetingAccessType)
            }
          >
            <option value="ALL">Tất cả loại phòng</option>
            <option value="PRIVATE">{MANAGEMENT_MEETING_ACCESS_TYPE_LABELS.PRIVATE}</option>
            <option value="PUBLIC">{MANAGEMENT_MEETING_ACCESS_TYPE_LABELS.PUBLIC}</option>
          </select>
          <span aria-hidden="true">
            <LineIcon name="chevron" />
          </span>
        </label>

        <button type="button" className={styles.presentationControl} aria-disabled="true">
          <span aria-hidden="true">
            <LineIcon name="calendar" />
          </span>
          <span>Chọn khoảng thời gian</span>
          <span aria-hidden="true">
            <LineIcon name="chevron" />
          </span>
        </button>

        <div className={styles.viewSwitcher} aria-label="Kiểu hiển thị">
          <button type="button" className={`${styles.viewButton} ${styles.viewButtonActive}`}>
            <LineIcon name="list" />
            <span>Danh sách</span>
          </button>
          <button type="button" className={styles.viewButton} aria-disabled="true">
            <LineIcon name="calendar" />
            <span>Lịch</span>
          </button>
        </div>
      </section>

      <section className={styles.dataPanel} aria-label="Danh sách cuộc họp">
        <div className={styles.dataPanelTop}>
          <strong className={styles.resultCount}>Tổng {filteredMeetings.length} cuộc họp</strong>
          <div className={styles.sortGroup}>
            <span>Sắp xếp theo:</span>
            <span className={styles.sortPresentation}>
              Thời gian bắt đầu (mới nhất)
              <LineIcon name="chevron" />
            </span>
          </div>
        </div>

        <div className={styles.listHeader} role="row">
          <span role="columnheader">Cuộc họp</span>
          <span role="columnheader">Thời gian</span>
          <span role="columnheader">Người tham gia</span>
          <span role="columnheader">Loại phòng</span>
          <span role="columnheader">Trạng thái</span>
          <span role="columnheader">Thao tác</span>
        </div>

        {loading ? <div className={styles.loadingState}>Đang tải danh sách cuộc họp...</div> : null}
        {error ? <div className={styles.errorState}>Không thể tải dữ liệu: {error}</div> : null}

        {!loading && !error ? (
          filteredMeetings.length > 0 ? (
            <div className={styles.meetingRows}>
              {filteredMeetings.map((meeting) => {
                const visibleParticipants = meeting.participants.slice(0, 3);
                const remainingParticipantCount = Math.max(
                  0,
                  meeting.participants.length - visibleParticipants.length,
                );

                return (
                  <article key={meeting.id} className={styles.meetingRow}>
                    <div className={styles.identityCell}>
                      <span className={styles.meetingIcon} aria-hidden="true">
                        <LineIcon name="video" />
                      </span>
                      <div className={styles.meetingIdentity}>
                        <h2 className={styles.meetingTitle}>{meeting.title}</h2>
                        <p className={styles.meetingCode}>Mã: {meeting.roomName}</p>
                      </div>
                    </div>

                    <div className={styles.timeCell} data-label="Thời gian">
                      <div className={styles.dateLine}>
                        <LineIcon name="calendar" />
                        <span>{formatMeetingDate(meeting.startTime)}</span>
                      </div>
                      <p className={styles.timeLine}>
                        {formatMeetingTime(meeting.startTime)} –{' '}
                        {formatMeetingTime(meeting.endTime)} (
                        {formatMeetingDuration(meeting.startTime, meeting.endTime)})
                      </p>
                    </div>

                    <div className={styles.participantCell} data-label="Người tham gia">
                      <div className={styles.avatarGroup} aria-hidden="true">
                        {visibleParticipants.map((participant) => (
                          <span
                            key={participant.participantKey ?? participant.userId}
                            className={styles.avatar}
                          >
                            {getInitials(participant.name)}
                          </span>
                        ))}
                        {remainingParticipantCount > 0 ? (
                          <span className={`${styles.avatar} ${styles.avatarMore}`}>
                            +{remainingParticipantCount}
                          </span>
                        ) : null}
                      </div>
                      <span className={styles.participantCount}>
                        {meeting.participants.length} người
                      </span>
                    </div>

                    <div className={styles.badgeCell} data-label="Loại phòng">
                      <AccessBadge
                        accessType={meeting.accessType}
                        isPointMeeting={meeting.creationMode === 'POINT'}
                      />
                    </div>

                    <div className={styles.badgeCell} data-label="Trạng thái">
                      <StatusBadge status={meeting.status} />
                    </div>

                    <div className={styles.actionCell}>
                      <button
                        type="button"
                        className={styles.joinButton}
                        onClick={() => router.push(`/join/${meeting.id}`)}
                      >
                        <LineIcon name="video" />
                        <span>Vào họp</span>
                      </button>
                      <Link
                        href={`/management/meetings/${meeting.id}`}
                        className={styles.detailButton}
                      >
                        <LineIcon name="document" />
                        <span>Xem chi tiết</span>
                      </Link>
                      {renderActions(meeting)}
                    </div>
                  </article>
                );
              })}
            </div>
          ) : (
            <EmptyState message="Không tìm thấy cuộc họp phù hợp với bộ lọc hiện tại." />
          )
        ) : null}
      </section>

      {selectedMeeting ? (
        <DeleteMeetingDialog
          meetingTitle={selectedMeeting.title}
          isDeleting={isDeleting}
          onCancel={() => setSelectedMeeting(null)}
          onConfirm={() => void handleDeleteMeeting()}
        />
      ) : null}
    </div>
  );
}
