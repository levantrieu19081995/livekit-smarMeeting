'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import toast from 'react-hot-toast';
import {
  endManagementMeeting,
  getManagementMeetingById,
  listManagementMeetings,
  overrideManagementMeeting,
  resetManagementMeetingMinutes,
} from '@/services/management/managementMeetingApi';
import {
  generateMinutes,
  getCurrentMinutes,
  getMinutesProcessing,
  getMinutesVersions,
  retryMinutes,
} from '@/services/management/managementMinutesService';
import { useManagementAuth } from '@/hooks/useManagementAuth';
import { MINUTES_PROCESSING_UI } from '@/components/management/minutes/minutesProcessingLabels';
import { MinutesStatusBadge } from '@/components/management/minutes/MinutesStatusBadge';
import type {
  ManagementMeeting,
  ManagementMeetingAccessType,
  ManagementMeetingStatus,
} from '@/types/management/meeting';
import {
  MANAGEMENT_MEETING_ACCESS_TYPE_LABELS,
  MANAGEMENT_MEETING_STATUS_LABELS,
} from '@/types/management/meeting';
import type {
  MeetingMinutesVersion,
  MinutesProcessingApiResponse,
  MinutesStatus,
} from '@/types/management/minutes';
import styles from '@/styles/management/ManagementSettings.module.css';

const PAGE_SIZE = 8;
const MEETING_STATUSES: ManagementMeetingStatus[] = [
  'UPCOMING',
  'IN_PROGRESS',
  'COMPLETED',
  'CANCELLED',
];

type AuditEntry = {
  id: string;
  timestamp: string;
  meetingId: string;
  action: string;
  before: string;
  after: string;
  actor: string;
  reason: string;
};

type TimeDraft = {
  startDate: string;
  startTime: string;
  endDate: string;
  endTime: string;
};

function splitDateTime(value?: string | null): { date: string; time: string } {
  if (!value) return { date: '', time: '' };
  const [datePart, rawTime = ''] = value.includes('T') ? value.split('T') : value.split(' ');
  return {
    date: datePart ?? '',
    time: rawTime.slice(0, 5),
  };
}

function toDraft(meeting: ManagementMeeting): TimeDraft {
  const start = splitDateTime(meeting.startTime);
  const end = splitDateTime(meeting.endTime);
  return {
    startDate: start.date,
    startTime: start.time,
    endDate: end.date,
    endTime: end.time,
  };
}

function composeDateTime(date: string, time: string) {
  if (!date || !time) return '';
  return `${date}T${time.length === 5 ? `${time}:00` : time}`;
}

function parseLocalDateTime(date: string, time: string) {
  const value = composeDateTime(date, time);
  const parsed = value ? new Date(value) : null;
  return parsed && Number.isFinite(parsed.getTime()) ? parsed : null;
}

function formatDateTime(value?: string | null) {
  if (!value) return '—';
  const parsed = new Date(value);
  if (!Number.isFinite(parsed.getTime())) return value;
  return new Intl.DateTimeFormat('vi-VN', {
    dateStyle: 'short',
    timeStyle: 'medium',
  }).format(parsed);
}

function formatDraftDateTime(date: string, time: string) {
  const value = composeDateTime(date, time);
  return value ? formatDateTime(value) : '—';
}

function durationLabel(startTime: string | null, endTime: string | null) {
  if (!startTime || !endTime) return 'Không đặt lịch kết thúc';
  const start = new Date(startTime).getTime();
  const end = new Date(endTime).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return '—';
  const minutes = Math.round((end - start) / 60_000);
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours > 0 && rest > 0) return `${hours} giờ ${rest} phút`;
  if (hours > 0) return `${hours} giờ`;
  return `${rest} phút`;
}

function toInputDate(date: Date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function toInputTime(date: Date) {
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

function MeetingStatusBadge({ status }: { status: ManagementMeetingStatus }) {
  return (
    <span className={`${styles.statusBadge} ${styles[`meeting${status}`]}`}>
      {MANAGEMENT_MEETING_STATUS_LABELS[status]}
    </span>
  );
}

export function ManagementSettingsPage() {
  const router = useRouter();
  const { user } = useManagementAuth();
  const actorName = user?.fullName || user?.email || 'Quản trị viên';
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<ManagementMeetingStatus | 'ALL'>('ALL');
  const [accessTypeFilter, setAccessTypeFilter] = useState<ManagementMeetingAccessType | 'ALL'>(
    'ALL',
  );
  const [page, setPage] = useState(0);
  const [meetings, setMeetings] = useState<ManagementMeeting[]>([]);
  const [meetingListLoading, setMeetingListLoading] = useState(false);
  const [meetingListError, setMeetingListError] = useState('');
  const [selectedMeetingId, setSelectedMeetingId] = useState<string | null>(null);
  const [selectedMeeting, setSelectedMeeting] = useState<ManagementMeeting | null>(null);
  const [currentMinutes, setCurrentMinutes] = useState<MeetingMinutesVersion | null>(null);
  const [minutesVersions, setMinutesVersions] = useState<MeetingMinutesVersion[]>([]);
  const [processing, setProcessing] = useState<MinutesProcessingApiResponse | null>(null);
  const [processingUnavailable, setProcessingUnavailable] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');
  const [timeDraft, setTimeDraft] = useState<TimeDraft>({
    startDate: '',
    startTime: '',
    endDate: '',
    endTime: '',
  });
  const [savingTime, setSavingTime] = useState(false);
  const [targetStatus, setTargetStatus] = useState<ManagementMeetingStatus | null>(null);
  const [statusReason, setStatusReason] = useState('');
  const [showStatusConfirm, setShowStatusConfirm] = useState(false);
  const [savingStatus, setSavingStatus] = useState(false);
  const [minutesActionLoading, setMinutesActionLoading] = useState(false);
  const [showResetMinutesConfirm, setShowResetMinutesConfirm] = useState(false);
  const [resettingMinutes, setResettingMinutes] = useState(false);
  const [auditEntries, setAuditEntries] = useState<AuditEntry[]>([]);
  const [auditFilter, setAuditFilter] = useState('ALL');
  const [auditFrom, setAuditFrom] = useState('');
  const [auditTo, setAuditTo] = useState('');

  const isAdmin = user?.systemRole === 'ADMIN';
  const minutesStatus: MinutesStatus = currentMinutes?.status
    ? currentMinutes.status
    : processing?.status === 'QUEUED' || processing?.status === 'PROCESSING'
      ? 'PROCESSING'
      : processing?.status === 'FAILED'
        ? 'FAILED'
        : 'NOT_GENERATED';
  const hasTimeChanges = selectedMeeting
    ? composeDateTime(timeDraft.startDate, timeDraft.startTime) !== selectedMeeting.startTime ||
      composeDateTime(timeDraft.endDate, timeDraft.endTime) !== selectedMeeting.endTime
    : false;
  const selectedTargetStatus = targetStatus ?? selectedMeeting?.status ?? null;
  const completedDisabledForCancelled =
    selectedMeeting?.status === 'CANCELLED' && selectedTargetStatus === 'COMPLETED';
  const canUpdateStatus =
    selectedMeeting != null &&
    selectedTargetStatus != null &&
    selectedTargetStatus !== selectedMeeting.status &&
    !completedDisabledForCancelled &&
    statusReason.trim().length > 0 &&
    !savingStatus;
  const canGenerateMinutes =
    selectedMeeting != null &&
    currentMinutes == null &&
    processing?.status !== 'QUEUED' &&
    processing?.status !== 'PROCESSING' &&
    processing?.status !== 'FAILED' &&
    !minutesActionLoading;
  const canRetryMinutes =
    selectedMeeting != null &&
    currentMinutes == null &&
    processing?.status === 'FAILED' &&
    !minutesActionLoading;
  const hasMinutesArtifacts =
    currentMinutes != null || minutesVersions.length > 0 || processing != null;
  const processingActive = processing?.status === 'QUEUED' || processing?.status === 'PROCESSING';
  const canResetMinutes =
    selectedMeeting?.status === 'COMPLETED' &&
    hasMinutesArtifacts &&
    !processingActive &&
    !resettingMinutes;
  const filteredAuditEntries = auditEntries.filter((entry) => {
    if (auditFilter !== 'ALL' && entry.action !== auditFilter) return false;
    if (auditFrom && entry.timestamp.slice(0, 10) < auditFrom) return false;
    if (auditTo && entry.timestamp.slice(0, 10) > auditTo) return false;
    return true;
  });

  const loadMeetings = useCallback(async () => {
    if (!isAdmin) return;
    setMeetingListLoading(true);
    setMeetingListError('');
    try {
      const response = await listManagementMeetings({
        search,
        status: statusFilter,
        accessType: accessTypeFilter,
        page,
        size: PAGE_SIZE,
        sort: 'startTime,desc',
      });
      setMeetings(response);
    } catch (error) {
      setMeetingListError(
        error instanceof Error ? error.message : 'Không thể tải danh sách cuộc họp.',
      );
    } finally {
      setMeetingListLoading(false);
    }
  }, [accessTypeFilter, isAdmin, page, search, statusFilter]);

  const loadSelectedMeeting = useCallback(async (meetingId: string) => {
    setDetailLoading(true);
    setDetailError('');
    setProcessingUnavailable(false);
    try {
      const [meetingResult, currentResult, versionsResult, processingResult] =
        await Promise.allSettled([
          getManagementMeetingById(meetingId),
          getCurrentMinutes(meetingId),
          getMinutesVersions(meetingId),
          getMinutesProcessing(meetingId),
        ]);

      if (meetingResult.status === 'rejected') {
        throw meetingResult.reason;
      }

      const meeting = meetingResult.value;
      setSelectedMeeting(meeting);
      setTimeDraft(toDraft(meeting));
      setTargetStatus(meeting.status);
      setStatusReason('');
      const versions = versionsResult.status === 'fulfilled' ? versionsResult.value : [];
      setCurrentMinutes(
        currentResult.status === 'fulfilled'
          ? (currentResult.value ?? versions[0] ?? null)
          : (versions[0] ?? null),
      );
      setMinutesVersions(versions);
      if (processingResult.status === 'fulfilled') {
        setProcessing(processingResult.value);
      } else {
        setProcessing(null);
        setProcessingUnavailable(true);
      }
    } catch (error) {
      setSelectedMeeting(null);
      setCurrentMinutes(null);
      setMinutesVersions([]);
      setProcessing(null);
      setDetailError(error instanceof Error ? error.message : 'Không thể tải chi tiết cuộc họp.');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadMeetings();
  }, [loadMeetings]);

  function appendAudit(entry: Omit<AuditEntry, 'id' | 'timestamp' | 'actor'>) {
    setAuditEntries((current) => [
      {
        ...entry,
        id: `${Date.now()}-${current.length}`,
        timestamp: new Date().toISOString(),
        actor: actorName,
      },
      ...current,
    ]);
  }

  function handleSearch() {
    setPage(0);
    void loadMeetings();
  }

  function handleRefresh() {
    void loadMeetings();
    if (selectedMeetingId) void loadSelectedMeeting(selectedMeetingId);
  }

  function handleSelectMeeting(meetingId: string) {
    setSelectedMeetingId(meetingId);
    void loadSelectedMeeting(meetingId);
  }

  function updateDraft(part: keyof TimeDraft, value: string) {
    setTimeDraft((current) => ({ ...current, [part]: value }));
  }

  function setEndFromDate(date: Date) {
    setTimeDraft((current) => ({
      ...current,
      endDate: toInputDate(date),
      endTime: toInputTime(date),
    }));
  }

  function shiftEndTime(minutes: number) {
    const base = parseLocalDateTime(timeDraft.endDate, timeDraft.endTime);
    if (!base) return;
    base.setMinutes(base.getMinutes() + minutes);
    setEndFromDate(base);
  }

  function resetTimeDraft() {
    if (selectedMeeting) setTimeDraft(toDraft(selectedMeeting));
  }

  async function saveTimeChanges() {
    if (!selectedMeeting || savingTime) return;
    const nextStart = composeDateTime(timeDraft.startDate, timeDraft.startTime);
    const nextEnd = composeDateTime(timeDraft.endDate, timeDraft.endTime);
    const startDate = parseLocalDateTime(timeDraft.startDate, timeDraft.startTime);
    const endDate = parseLocalDateTime(timeDraft.endDate, timeDraft.endTime);
    if (!startDate || !endDate) {
      toast.error('Thời gian không hợp lệ.');
      return;
    }
    if (endDate <= startDate) {
      toast.error('Thời gian kết thúc phải sau thời gian bắt đầu.');
      return;
    }
    if (!hasTimeChanges) {
      toast.error('Chưa có thay đổi thời gian để lưu.');
      return;
    }

    try {
      setSavingTime(true);
      await overrideManagementMeeting(selectedMeeting.id, {
        startTime: nextStart,
        endTime: nextEnd,
        reason: 'Điều chỉnh thời gian từ công cụ quản trị nội bộ',
      });
      await Promise.all([loadSelectedMeeting(selectedMeeting.id), loadMeetings()]);
      appendAudit({
        meetingId: selectedMeeting.id,
        action: 'Cập nhật thời gian',
        before: `${formatDateTime(selectedMeeting.startTime)} → ${formatDateTime(selectedMeeting.endTime)}`,
        after: `${formatDateTime(nextStart)} → ${formatDateTime(nextEnd)}`,
        reason: 'Điều chỉnh thời gian từ công cụ quản trị nội bộ',
      });
      toast.success('Đã lưu thay đổi thời gian.');
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Không thể lưu thay đổi thời gian.');
    } finally {
      setSavingTime(false);
    }
  }

  async function confirmStatusUpdate() {
    if (!selectedMeeting || !selectedTargetStatus || savingStatus) return;
    try {
      setSavingStatus(true);
      let cleanupPending = false;
      if (selectedTargetStatus === 'COMPLETED') {
        cleanupPending = await endManagementMeeting(selectedMeeting.id);
      } else {
        cleanupPending = await overrideManagementMeeting(selectedMeeting.id, {
          status: selectedTargetStatus,
          reason: statusReason.trim(),
        });
      }
      await Promise.all([loadSelectedMeeting(selectedMeeting.id), loadMeetings()]);
      appendAudit({
        meetingId: selectedMeeting.id,
        action: 'Cập nhật trạng thái',
        before: selectedMeeting.status,
        after: selectedTargetStatus,
        reason: statusReason.trim(),
      });
      setShowStatusConfirm(false);
      toast.success(
        cleanupPending
          ? 'Đã cập nhật trạng thái; hệ thống đang đóng các kết nối còn lại.'
          : 'Đã cập nhật trạng thái cuộc họp.',
      );
    } catch (error) {
      toast.error(
        error instanceof Error ? error.message : 'Không thể cập nhật trạng thái cuộc họp.',
      );
    } finally {
      setSavingStatus(false);
    }
  }

  async function handleGenerateMinutes() {
    if (!selectedMeeting || !canGenerateMinutes) return;
    try {
      setMinutesActionLoading(true);
      await generateMinutes(selectedMeeting.id);
      await loadSelectedMeeting(selectedMeeting.id);
      appendAudit({
        meetingId: selectedMeeting.id,
        action: 'Tạo biên bản',
        before: 'NOT_GENERATED',
        after: 'PROCESSING',
        reason: 'Khởi tạo xử lý từ công cụ quản trị nội bộ',
      });
      toast.success('Đã gửi yêu cầu tạo biên bản.');
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Không thể tạo biên bản.');
    } finally {
      setMinutesActionLoading(false);
    }
  }

  async function handleRetryMinutes() {
    if (!selectedMeeting || !canRetryMinutes) return;
    try {
      setMinutesActionLoading(true);
      await retryMinutes(selectedMeeting.id);
      await loadSelectedMeeting(selectedMeeting.id);
      appendAudit({
        meetingId: selectedMeeting.id,
        action: 'Retry xử lý',
        before: processing?.status ?? 'FAILED',
        after: 'PROCESSING',
        reason: 'Thử lại xử lý từ công cụ quản trị nội bộ',
      });
      toast.success('Đã gửi yêu cầu thử lại xử lý.');
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Không thể thử lại xử lý.');
    } finally {
      setMinutesActionLoading(false);
    }
  }

  async function handleResetMinutes() {
    if (!selectedMeeting || resettingMinutes) return;
    const meetingId = selectedMeeting.id;
    const beforeStatus = minutesStatus;
    const beforeVersion = currentMinutes?.version ? ` v${currentMinutes.version}` : '';

    try {
      setResettingMinutes(true);
      await resetManagementMeetingMinutes(meetingId);
      await Promise.all([loadSelectedMeeting(meetingId), loadMeetings()]);
      appendAudit({
        meetingId,
        action: 'Khôi phục chưa tạo biên bản',
        before: `${beforeStatus}${beforeVersion}`,
        after: 'NOT_GENERATED',
        reason: 'Xóa dữ liệu hậu xử lý, giữ nguyên capture/manifest',
      });
      setShowResetMinutesConfirm(false);
      toast.success('Đã khôi phục cuộc họp về trạng thái chưa tạo biên bản.');
    } catch (error) {
      toast.error(
        error instanceof Error
          ? error.message
          : 'Không thể khôi phục cuộc họp về trạng thái chưa tạo biên bản.',
      );
    } finally {
      setResettingMinutes(false);
    }
  }

  const auditActions = useMemo(
    () => Array.from(new Set(auditEntries.map((entry) => entry.action))),
    [auditEntries],
  );

  if (!isAdmin) {
    return (
      <section className={styles.accessDenied}>
        Bạn không có quyền truy cập công cụ quản trị nội bộ.
      </section>
    );
  }

  return (
    <div className={styles.settingsPage}>
      <section className={styles.pageHeader}>
        <div>
          <p>Công cụ quản trị nội bộ cho cuộc họp & biên bản</p>
        </div>
        <div className={styles.warningBanner}>
          <span aria-hidden="true">⚠</span>
          <p>
            <strong>CẢNH BÁO:</strong> Các thay đổi tại đây tác động trực tiếp đến dữ liệu cuộc họp,
            biên bản và tiến trình xử lý.
          </p>
        </div>
      </section>

      <div className={styles.settingsLayout}>
        <section className={`${styles.sectionCard} ${styles.sectionPurple}`}>
          <SectionTitle number="1" title="Tra cứu cuộc họp" />
          <div className={styles.searchStack}>
            <label className={styles.field}>
              <span>Tìm kiếm</span>
              <div className={styles.searchInput}>
                <span aria-hidden="true">⌕</span>
                <input
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                  placeholder="Tìm theo ID, mã cuộc họp, phòng, tiêu đề..."
                />
              </div>
            </label>
            <label className={styles.field}>
              <span>Trạng thái</span>
              <select
                value={statusFilter}
                onChange={(event) =>
                  setStatusFilter(event.target.value as ManagementMeetingStatus | 'ALL')
                }
              >
                <option value="ALL">Tất cả</option>
                {MEETING_STATUSES.map((status) => (
                  <option key={status} value={status}>
                    {MANAGEMENT_MEETING_STATUS_LABELS[status]}
                  </option>
                ))}
              </select>
            </label>
            <label className={styles.field}>
              <span>Loại phòng</span>
              <select
                value={accessTypeFilter}
                onChange={(event) =>
                  setAccessTypeFilter(event.target.value as ManagementMeetingAccessType | 'ALL')
                }
              >
                <option value="ALL">Tất cả</option>
                <option value="PRIVATE">{MANAGEMENT_MEETING_ACCESS_TYPE_LABELS.PRIVATE}</option>
                <option value="PUBLIC">{MANAGEMENT_MEETING_ACCESS_TYPE_LABELS.PUBLIC}</option>
              </select>
            </label>
            <div className={styles.buttonRow}>
              <button type="button" className={styles.primaryButton} onClick={handleSearch}>
                Tìm kiếm
              </button>
              <button type="button" className={styles.secondaryButton} onClick={handleRefresh}>
                Làm mới
              </button>
            </div>
          </div>

          <div className={styles.resultHeader}>Kết quả ({meetings.length})</div>
          {meetingListError ? <p className={styles.errorText}>{meetingListError}</p> : null}
          <div className={styles.meetingList}>
            {meetingListLoading ? (
              <p className={styles.mutedText}>Đang tải danh sách cuộc họp...</p>
            ) : meetings.length === 0 ? (
              <p className={styles.mutedText}>Không có cuộc họp phù hợp.</p>
            ) : (
              meetings.map((meeting) => (
                <button
                  key={meeting.id}
                  type="button"
                  className={`${styles.meetingItem} ${
                    selectedMeetingId === meeting.id ? styles.meetingItemActive : ''
                  }`}
                  onClick={() => handleSelectMeeting(meeting.id)}
                >
                  <span>#{meeting.id}</span>
                  <strong>{meeting.title}</strong>
                  <small>
                    {formatDateTime(meeting.startTime)} → {formatDateTime(meeting.endTime)}
                  </small>
                  <span className={styles.badgeRow}>
                    <MeetingStatusBadge status={meeting.status} />
                  </span>
                </button>
              ))
            )}
          </div>
          <div className={styles.pagination}>
            <button
              type="button"
              onClick={() => setPage((value) => Math.max(0, value - 1))}
              disabled={page === 0}
            >
              ‹
            </button>
            {page > 0 ? (
              <button type="button" onClick={() => setPage(page - 1)}>
                {page}
              </button>
            ) : null}
            <button type="button" className={styles.currentPage}>
              {page + 1}
            </button>
            {meetings.length === PAGE_SIZE ? (
              <button type="button" onClick={() => setPage(page + 1)}>
                {page + 2}
              </button>
            ) : null}
            <button
              type="button"
              onClick={() => setPage((value) => value + 1)}
              disabled={meetings.length < PAGE_SIZE}
            >
              ›
            </button>
          </div>
        </section>

        {selectedMeetingId ? (
          <div className={styles.middleGrid}>
            <section className={`${styles.sectionCard} ${styles.sectionPurple}`}>
              <SectionTitle number="2" title="Thông tin hiện tại của cuộc họp" />
              {detailLoading ? (
                <p className={styles.mutedText}>Đang tải chi tiết cuộc họp...</p>
              ) : detailError ? (
                <p className={styles.errorText}>{detailError}</p>
              ) : selectedMeeting ? (
                <div className={styles.snapshotGrid}>
                  <InfoItem label="Meeting ID" value={selectedMeeting.id} />
                  <InfoItem label="Mã cuộc họp" value={selectedMeeting.meetingCode ?? '—'} />
                  <InfoItem label="Tên phòng" value={selectedMeeting.roomName || '—'} />
                  <InfoItem label="Tiêu đề" value={selectedMeeting.title} />
                  <InfoItem
                    label="Loại phòng"
                    value={MANAGEMENT_MEETING_ACCESS_TYPE_LABELS[selectedMeeting.accessType]}
                  />
                  <InfoItem
                    label="Chủ tọa"
                    value={
                      selectedMeeting.participants.find((item) => item.role === 'CHAIRMAN')?.name ??
                      'Chưa có dữ liệu'
                    }
                  />
                  <InfoItem
                    label="Thời gian bắt đầu"
                    value={formatDateTime(selectedMeeting.startTime)}
                  />
                  <InfoItem
                    label="Thời gian kết thúc"
                    value={formatDateTime(selectedMeeting.endTime)}
                  />
                  <InfoItem
                    label="Thời lượng"
                    value={durationLabel(selectedMeeting.startTime, selectedMeeting.endTime)}
                  />
                  <InfoItem
                    label="Trạng thái cuộc họp"
                    value={<MeetingStatusBadge status={selectedMeeting.status} />}
                  />
                  <InfoItem
                    label="Trạng thái biên bản"
                    value={<MinutesStatusBadge status={minutesStatus} />}
                  />
                  <InfoItem
                    label="Trạng thái xử lý STT/Minutes"
                    value={
                      processing?.stage
                        ? MINUTES_PROCESSING_UI[processing.stage]?.processingText
                        : 'Chưa có dữ liệu'
                    }
                  />
                  <InfoItem
                    label="Tiến độ %"
                    value={processing ? `${processing.progressPercent}%` : '—'}
                  />
                </div>
              ) : (
                <p className={styles.emptyState}>
                  Chọn một cuộc họp ở danh sách bên trái để xem chi tiết.
                </p>
              )}
            </section>

            <section className={`${styles.sectionCard} ${styles.sectionBlue}`}>
              <SectionTitle number="3" title="Sửa thời gian cuộc họp" />
              <div className={styles.timeGrid}>
                <label className={styles.field}>
                  <span>Thời gian bắt đầu</span>
                  <input
                    type="date"
                    value={timeDraft.startDate}
                    onChange={(event) => updateDraft('startDate', event.target.value)}
                    disabled={!selectedMeeting}
                  />
                </label>
                <label className={styles.field}>
                  <span>&nbsp;</span>
                  <input
                    type="time"
                    value={timeDraft.startTime}
                    onChange={(event) => updateDraft('startTime', event.target.value)}
                    disabled={!selectedMeeting}
                  />
                </label>
                <label className={styles.field}>
                  <span>Thời gian kết thúc</span>
                  <input
                    type="date"
                    value={timeDraft.endDate}
                    onChange={(event) => updateDraft('endDate', event.target.value)}
                    disabled={!selectedMeeting}
                  />
                </label>
                <label className={styles.field}>
                  <span>&nbsp;</span>
                  <input
                    type="time"
                    value={timeDraft.endTime}
                    onChange={(event) => updateDraft('endTime', event.target.value)}
                    disabled={!selectedMeeting}
                  />
                </label>
              </div>
              <div className={styles.quickActions}>
                <span>Thao tác nhanh</span>
                <button
                  type="button"
                  onClick={() => setEndFromDate(new Date())}
                  disabled={!selectedMeeting}
                >
                  Kết thúc = hiện tại
                </button>
                <button
                  type="button"
                  onClick={() => {
                    const date = new Date();
                    date.setMinutes(date.getMinutes() - 1);
                    setEndFromDate(date);
                  }}
                  disabled={!selectedMeeting}
                >
                  Kết thúc = hiện tại - 1 phút
                </button>
                {[15, 30, 60, -15, -30].map((minutes) => (
                  <button
                    key={minutes}
                    type="button"
                    onClick={() => shiftEndTime(minutes)}
                    disabled={!selectedMeeting}
                  >
                    {minutes > 0 ? '+' : ''}
                    {minutes === 60 ? '1 giờ' : `${minutes} phút`}
                  </button>
                ))}
              </div>
              <div className={styles.previewBox}>
                <strong>Xem trước thay đổi</strong>
                <PreviewRow
                  label="Bắt đầu"
                  oldValue={formatDateTime(selectedMeeting?.startTime)}
                  newValue={formatDraftDateTime(timeDraft.startDate, timeDraft.startTime)}
                  changed={
                    selectedMeeting
                      ? composeDateTime(timeDraft.startDate, timeDraft.startTime) !==
                        selectedMeeting.startTime
                      : false
                  }
                />
                <PreviewRow
                  label="Kết thúc"
                  oldValue={formatDateTime(selectedMeeting?.endTime)}
                  newValue={formatDraftDateTime(timeDraft.endDate, timeDraft.endTime)}
                  changed={
                    selectedMeeting
                      ? composeDateTime(timeDraft.endDate, timeDraft.endTime) !==
                        selectedMeeting.endTime
                      : false
                  }
                />
              </div>
              <div className={styles.buttonRow}>
                <button
                  type="button"
                  className={styles.secondaryButton}
                  onClick={resetTimeDraft}
                  disabled={!selectedMeeting || !hasTimeChanges}
                >
                  Hoàn tác
                </button>
                <button
                  type="button"
                  className={styles.primaryButton}
                  onClick={saveTimeChanges}
                  disabled={!selectedMeeting || !hasTimeChanges || savingTime}
                >
                  {savingTime ? 'Đang lưu...' : 'Lưu thay đổi'}
                </button>
              </div>
            </section>

            <section className={`${styles.sectionCard} ${styles.sectionGreen}`}>
              <SectionTitle number="4" title="Sửa trạng thái cuộc họp" />
              <div className={styles.statusOptions}>
                {MEETING_STATUSES.map((status) => (
                  <button
                    key={status}
                    type="button"
                    className={`${styles.statusOption} ${
                      selectedMeeting?.status === status ? styles.statusCurrent : ''
                    } ${selectedTargetStatus === status ? styles.statusSelected : ''}`}
                    onClick={() => setTargetStatus(status)}
                    disabled={
                      !selectedMeeting ||
                      (selectedMeeting.status === 'CANCELLED' && status === 'COMPLETED')
                    }
                  >
                    <span>{status}</span>
                    <strong>{MANAGEMENT_MEETING_STATUS_LABELS[status]}</strong>
                  </button>
                ))}
              </div>
              <label className={styles.field}>
                <span>Lý do thay đổi (bắt buộc)</span>
                <textarea
                  value={statusReason}
                  onChange={(event) => setStatusReason(event.target.value)}
                  placeholder="Nhập lý do thay đổi trạng thái..."
                  disabled={!selectedMeeting}
                />
              </label>
              <button
                type="button"
                className={styles.primaryButton}
                onClick={() => setShowStatusConfirm(true)}
                disabled={!canUpdateStatus}
              >
                {savingStatus ? 'Đang lưu...' : 'Cập nhật trạng thái'}
              </button>
            </section>

            <section className={`${styles.sectionCard} ${styles.sectionPurple}`}>
              <SectionTitle number="5" title="Can thiệp biên bản & xử lý" />
              <div className={styles.minutesSummary}>
                <span>Trạng thái biên bản hiện tại:</span>
                <MinutesStatusBadge status={minutesStatus} />
              </div>
              <div className={styles.actionGrid}>
                <button
                  type="button"
                  onClick={() =>
                    selectedMeeting && router.push(`/management/minutes/${selectedMeeting.id}`)
                  }
                  disabled={!selectedMeeting || !currentMinutes}
                >
                  Mở lại để sửa nội dung
                </button>
                <button
                  type="button"
                  onClick={() =>
                    selectedMeeting && router.push(`/management/minutes/${selectedMeeting.id}`)
                  }
                  disabled={!selectedMeeting || !currentMinutes?.version}
                >
                  Xem version hiện tại{' '}
                  {currentMinutes?.version ? `(v${currentMinutes.version})` : ''}
                </button>
                <button
                  type="button"
                  onClick={handleGenerateMinutes}
                  disabled={!canGenerateMinutes}
                >
                  {minutesActionLoading ? 'Đang xử lý...' : 'Tạo biên bản'}
                </button>
                <button type="button" onClick={handleRetryMinutes} disabled={!canRetryMinutes}>
                  Retry xử lý
                </button>
                <button
                  type="button"
                  onClick={() =>
                    selectedMeeting && router.push(`/management/minutes/${selectedMeeting.id}`)
                  }
                  disabled={!selectedMeeting}
                >
                  Xem transcript
                </button>
                <button
                  type="button"
                  className={styles.resetMinutesButton}
                  onClick={() => setShowResetMinutesConfirm(true)}
                  disabled={!canResetMinutes}
                >
                  {resettingMinutes ? 'Đang xử lý...' : 'Khôi phục về chưa tạo biên bản'}
                </button>
              </div>
              <div className={styles.processingBox}>
                <strong>Thông tin xử lý</strong>
                {processing ? (
                  <dl>
                    <div>
                      <dt>Stage hiện tại</dt>
                      <dd>{MINUTES_PROCESSING_UI[processing.stage]?.processingText}</dd>
                    </div>
                    <div>
                      <dt>Tiến độ</dt>
                      <dd>{processing.progressPercent}%</dd>
                    </div>
                    <div>
                      <dt>Cập nhật lúc</dt>
                      <dd>{formatDateTime(processing.updatedAt)}</dd>
                    </div>
                  </dl>
                ) : (
                  <p>
                    {processingUnavailable ? 'Chưa có tiến trình xử lý.' : 'Chưa có dữ liệu xử lý.'}
                  </p>
                )}
              </div>
            </section>
          </div>
        ) : null}

        <section className={`${styles.sectionCard} ${styles.sectionOrange}`}>
          <SectionTitle number="6" title="Nhật ký thao tác" />
          <div className={styles.auditHeader}>
            <strong>Nhật ký phiên hiện tại</strong>
            <label>
              <span>Lọc theo hành động</span>
              <select value={auditFilter} onChange={(event) => setAuditFilter(event.target.value)}>
                <option value="ALL">Tất cả</option>
                {auditActions.map((action) => (
                  <option key={action} value={action}>
                    {action}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>Từ ngày</span>
              <input
                type="date"
                value={auditFrom}
                onChange={(event) => setAuditFrom(event.target.value)}
              />
            </label>
            <label>
              <span>Đến ngày</span>
              <input
                type="date"
                value={auditTo}
                onChange={(event) => setAuditTo(event.target.value)}
              />
            </label>
            <button
              type="button"
              className={styles.secondaryButton}
              onClick={handleRefresh}
              aria-label="Làm mới dữ liệu"
            >
              ↻
            </button>
          </div>
          <div className={styles.tableScroll}>
            <table className={styles.auditTable}>
              <thead>
                <tr>
                  <th>Thời gian</th>
                  <th>Meeting ID</th>
                  <th>Hành động</th>
                  <th>Thay đổi</th>
                  <th>Người thực hiện</th>
                  <th>Lý do</th>
                </tr>
              </thead>
              <tbody>
                {filteredAuditEntries.length === 0 ? (
                  <tr>
                    <td colSpan={6}>Chưa có thao tác trong phiên hiện tại.</td>
                  </tr>
                ) : (
                  filteredAuditEntries.map((entry) => (
                    <tr key={entry.id}>
                      <td>{formatDateTime(entry.timestamp)}</td>
                      <td>#{entry.meetingId}</td>
                      <td>{entry.action}</td>
                      <td>
                        {entry.before} → {entry.after}
                      </td>
                      <td>{entry.actor}</td>
                      <td>{entry.reason}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </section>
      </div>

      {showStatusConfirm && selectedMeeting && selectedTargetStatus ? (
        <div
          className={styles.dialogOverlay}
          role="presentation"
          onMouseDown={() => setShowStatusConfirm(false)}
        >
          <section
            className={styles.confirmDialog}
            role="dialog"
            aria-modal="true"
            aria-labelledby="settings-status-confirm-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <h3 id="settings-status-confirm-title">Xác nhận cập nhật trạng thái</h3>
            <p>Bạn có chắc muốn chuyển trạng thái cuộc họp:</p>
            <div className={styles.statusConfirmFlow}>
              <strong>{selectedMeeting.status}</strong>
              <span>→</span>
              <strong>{selectedTargetStatus}</strong>
            </div>
            <p>Các thao tác này có thể ảnh hưởng đến luồng cuộc họp và biên bản.</p>
            <div className={styles.dialogActions}>
              <button
                type="button"
                className={styles.secondaryButton}
                onClick={() => setShowStatusConfirm(false)}
              >
                Hủy
              </button>
              <button
                type="button"
                className={styles.primaryButton}
                onClick={() => void confirmStatusUpdate()}
                disabled={savingStatus}
              >
                {savingStatus ? 'Đang lưu...' : 'Xác nhận'}
              </button>
            </div>
          </section>
        </div>
      ) : null}

      {showResetMinutesConfirm && selectedMeeting ? (
        <div
          className={styles.dialogOverlay}
          role="presentation"
          onMouseDown={() => setShowResetMinutesConfirm(false)}
        >
          <section
            className={styles.confirmDialog}
            role="dialog"
            aria-modal="true"
            aria-labelledby="settings-reset-minutes-confirm-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <h3 id="settings-reset-minutes-confirm-title">Khôi phục về chưa tạo biên bản?</h3>
            <p>
              Thao tác này sẽ xóa các phiên bản biên bản, transcript đã lưu và lịch sử xử lý của
              cuộc họp.
            </p>
            <p>Bản ghi âm, capture session và manifest.json sẽ được giữ nguyên.</p>
            <p>Sau khi khôi phục, bạn có thể nhấn &apos;Tạo biên bản&apos; để chạy lại từ đầu.</p>
            <div className={styles.dialogActions}>
              <button
                type="button"
                className={styles.secondaryButton}
                onClick={() => setShowResetMinutesConfirm(false)}
                disabled={resettingMinutes}
              >
                Hủy
              </button>
              <button
                type="button"
                className={styles.primaryButton}
                onClick={() => void handleResetMinutes()}
                disabled={resettingMinutes}
              >
                {resettingMinutes ? 'Đang xử lý...' : 'Khôi phục'}
              </button>
            </div>
          </section>
        </div>
      ) : null}
    </div>
  );
}

function SectionTitle({ number, title }: { number: string; title: string }) {
  return (
    <div className={styles.sectionTitle}>
      <span>{number}</span>
      <h3>{title}</h3>
    </div>
  );
}

function InfoItem({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className={styles.infoItem}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function PreviewRow({
  label,
  oldValue,
  newValue,
  changed,
}: {
  label: string;
  oldValue: string;
  newValue: string;
  changed: boolean;
}) {
  return (
    <div className={styles.previewRow}>
      <div>
        <span>{label} (cũ)</span>
        <strong>{oldValue}</strong>
      </div>
      <span aria-hidden="true">→</span>
      <div>
        <span>{label} (mới)</span>
        <strong className={changed ? styles.changedValue : ''}>{newValue}</strong>
      </div>
    </div>
  );
}
