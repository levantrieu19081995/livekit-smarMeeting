import { type ReactNode, useState } from 'react';
import { MinutesActionMenu } from './MinutesActionMenu';
import { MinutesParticipantAvatars } from './MinutesParticipantAvatars';
import type {
  MeetingMinutesListItem,
  MinutesParticipantRole,
  MinutesStatus,
  MinutesTableAction,
} from '@/types/management/minutes';
import styles from '@/styles/management/ManagementMinutesList.module.css';

interface MinutesTableProps {
  meetings: MeetingMinutesListItem[];
  total: number;
  page: number;
  pageSize: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (pageSize: number) => void;
  onAction: (meeting: MeetingMinutesListItem, action: MinutesTableAction) => void;
  canManageMeeting: (meeting: MeetingMinutesListItem) => boolean;
}

type TableIconName = 'calendar' | 'chevronLeft' | 'chevronRight' | 'edit' | 'eye' | 'filePlus';

function TableIcon({ name }: { name: TableIconName }) {
  const paths: Record<TableIconName, ReactNode> = {
    calendar: (
      <>
        <path d="M7 3v3" />
        <path d="M17 3v3" />
        <rect x="3.5" y="5" width="17" height="16" rx="2.5" />
        <path d="M3.5 9.5h17" />
      </>
    ),
    chevronLeft: <path d="m15 18-6-6 6-6" />,
    chevronRight: <path d="m9 18 6-6-6-6" />,
    edit: (
      <>
        <path d="M4 17.5V20h2.5L17.8 8.7l-2.5-2.5L4 17.5Z" />
        <path d="m14.5 7 2.5 2.5" />
      </>
    ),
    eye: (
      <>
        <path d="M2.5 12s3.5-6 9.5-6 9.5 6 9.5 6-3.5 6-9.5 6-9.5-6-9.5-6Z" />
        <circle cx="12" cy="12" r="2.5" />
      </>
    ),
    filePlus: (
      <>
        <path d="M6 3.5h8l4 4v13H6z" />
        <path d="M14 3.5v4h4" />
        <path d="M12 11v6" />
        <path d="M9 14h6" />
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

function formatDate(value: string) {
  return new Intl.DateTimeFormat('vi-VN').format(new Date(`${value}T00:00:00`));
}

function formatDateTime(value?: string) {
  if (!value) return '—';
  return new Intl.DateTimeFormat('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function primaryAction(status: MinutesStatus) {
  if (status === 'NOT_GENERATED') return { label: 'Tạo ngay', icon: 'edit' as const };
  if (status === 'DRAFT') return { label: 'Tiếp tục', icon: 'edit' as const };
  if (status === 'FAILED') return { label: 'Thử lại', icon: 'filePlus' as const };
  return { label: 'Xem', icon: 'eye' as const };
}

const ROLE_LABELS: Record<MinutesParticipantRole, string> = {
  CHAIRMAN: 'Chủ tọa',
  SECRETARY: 'Thư ký',
  MEMBER: 'Thành viên',
};

const STATUS_LABELS: Record<MinutesStatus, string> = {
  NOT_GENERATED: 'Chưa tạo',
  PROCESSING: 'Đang xử lý',
  DRAFT: 'Bản nháp',
  FINAL: 'Hoàn tất',
  FAILED: 'Lỗi xử lý',
};

const STATUS_ICONS: Record<MinutesStatus, ReactNode> = {
  NOT_GENERATED: (
    <>
      <path d="M6 3.5h8l4 4v13H6z" />
      <path d="M14 3.5v4h4" />
    </>
  ),
  PROCESSING: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7.5V12l3 2" />
    </>
  ),
  DRAFT: (
    <>
      <path d="M4 17.5V20h2.5L17.8 8.7l-2.5-2.5L4 17.5Z" />
      <path d="m14.5 7 2.5 2.5" />
    </>
  ),
  FINAL: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="m8.5 12 2.2 2.2 4.8-5" />
    </>
  ),
  FAILED: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 8v5" />
      <path d="M12 16h.01" />
    </>
  ),
};

function StatusBadge({ status }: { status: MinutesStatus }) {
  return (
    <span className={`${styles.statusBadge} ${styles[`status${status}`]}`}>
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth={1.8}
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        {STATUS_ICONS[status]}
      </svg>
      {STATUS_LABELS[status]}
    </span>
  );
}

function visiblePageNumbers(page: number, totalPages: number) {
  const start = Math.max(1, Math.min(page - 1, totalPages - 2));
  const end = Math.min(totalPages, start + 2);
  return Array.from({ length: end - start + 1 }, (_, index) => start + index);
}

export function MinutesTable({
  meetings,
  total,
  page,
  pageSize,
  onPageChange,
  onPageSizeChange,
  onAction,
  canManageMeeting,
}: MinutesTableProps) {
  const [openMeetingId, setOpenMeetingId] = useState<string | null>(null);
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const first = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const last = Math.min(page * pageSize, total);
  const pages = visiblePageNumbers(page, totalPages);

  return (
    <>
      <div className={styles.tableScroller}>
        <table className={styles.minutesTable}>
          <thead>
            <tr>
              <th className={styles.indexColumn}>#</th>
              <th>Cuộc họp</th>
              <th>Thời gian</th>
              <th>Chủ tọa</th>
              <th>Người tham dự</th>
              <th>Trạng thái biên bản</th>
              <th>Cập nhật lần cuối</th>
              <th>Thao tác</th>
            </tr>
          </thead>
          <tbody>
            {meetings.map((meeting, index) => {
              const action = canManageMeeting(meeting)
                ? primaryAction(meeting.status)
                : { label: 'Xem biên bản', icon: 'eye' as const };
              return (
                <tr key={meeting.meetingId}>
                  <td data-label="#" className={styles.indexCell}>
                    {first + index}
                  </td>
                  <td data-label="Cuộc họp">
                    <div className={styles.meetingCell}>
                      <strong title={meeting.title}>{meeting.title}</strong>
                      <span title={meeting.meetingCode}>{meeting.meetingCode}</span>
                    </div>
                  </td>
                  <td data-label="Thời gian">
                    <div className={styles.timeCell}>
                      <TableIcon name="calendar" />
                      <div>
                        <strong>{formatDate(meeting.date)}</strong>
                        <span>
                          {meeting.startTime} - {meeting.endTime}
                        </span>
                      </div>
                    </div>
                  </td>
                  <td data-label="Chủ tọa">
                    <div className={styles.chairmanCell}>
                      <span>{meeting.chairmanName.slice(0, 2).toUpperCase()}</span>
                      <div>
                        <strong title={meeting.chairmanName}>{meeting.chairmanName}</strong>
                        <small>
                          Vai trò: {meeting.myRole ? ROLE_LABELS[meeting.myRole] : 'Người xem'}
                        </small>
                      </div>
                    </div>
                  </td>
                  <td data-label="Người tham dự">
                    <MinutesParticipantAvatars
                      participants={meeting.participants}
                      total={meeting.participantCount}
                    />
                  </td>
                  <td data-label="Trạng thái biên bản">
                    <div className={styles.statusCell}>
                      <StatusBadge status={meeting.status} />
                      {meeting.status === 'PROCESSING' ? (
                        <span>Đang STT {meeting.progress ?? 0}%</span>
                      ) : null}
                    </div>
                  </td>
                  <td data-label="Cập nhật lần cuối">
                    <div className={styles.updatedCell}>
                      <strong>{formatDateTime(meeting.updatedAt)}</strong>
                      {meeting.currentVersion ? (
                        <span>
                          Version {meeting.currentVersion}
                          {meeting.status === 'FINAL' ? ' (Final)' : ''}
                        </span>
                      ) : (
                        <span>Chưa có biên bản</span>
                      )}
                    </div>
                  </td>
                  <td data-label="Thao tác">
                    <div className={styles.rowActions}>
                      <button
                        type="button"
                        className={styles.primaryRowAction}
                        onClick={() => onAction(meeting, 'PRIMARY')}
                      >
                        <TableIcon name={action.icon} />
                        <span>{action.label}</span>
                      </button>
                      <span className={styles.menuSlot}>
                        <MinutesActionMenu
                          meeting={meeting}
                          open={openMeetingId === meeting.meetingId}
                          onOpen={() => setOpenMeetingId(meeting.meetingId)}
                          onClose={() => setOpenMeetingId(null)}
                          onAction={(action) => onAction(meeting, action)}
                        />
                      </span>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div className={styles.pagination}>
        <span>
          Hiển thị {first} - {last} trong tổng số {total} biên bản
        </span>
        <div className={styles.paginationControls}>
          <button
            type="button"
            onClick={() => onPageChange(page - 1)}
            disabled={page <= 1}
            aria-label="Trang trước"
          >
            <TableIcon name="chevronLeft" />
          </button>
          {pages.map((pageNumber) => (
            <button
              key={pageNumber}
              type="button"
              className={pageNumber === page ? styles.pageActive : ''}
              onClick={() => onPageChange(pageNumber)}
            >
              {pageNumber}
            </button>
          ))}
          <button
            type="button"
            onClick={() => onPageChange(page + 1)}
            disabled={page >= totalPages}
            aria-label="Trang sau"
          >
            <TableIcon name="chevronRight" />
          </button>
          <label className={styles.pageSize}>
            <span className={styles.visuallyHidden}>Số dòng mỗi trang</span>
            <select
              value={pageSize}
              onChange={(event) => onPageSizeChange(Number(event.target.value))}
            >
              <option value={5}>5 / trang</option>
              <option value={10}>10 / trang</option>
            </select>
          </label>
        </div>
      </div>
    </>
  );
}
