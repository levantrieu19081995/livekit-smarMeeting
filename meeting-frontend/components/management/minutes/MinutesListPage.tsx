'use client';

import { HomeBrandLink } from '@/components/home/HomeBrandLink';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import toast from 'react-hot-toast';
import { GenerateMinutesDialog, ProcessingMinutesDialog } from './MinutesDialogs';
import { MinutesFilters } from './MinutesFilters';
import { MinutesSummaryCards } from './MinutesSummaryCards';
import { MinutesTable } from './MinutesTable';
import {
  generateMinutes,
  getMinutes,
  getMinutesSummary,
  retryMinutes,
} from '@/services/management/managementMinutesService';
import type {
  MeetingMinutesListItem,
  MinutesListFilters,
  MinutesSummary,
  MinutesTableAction,
} from '@/types/management/minutes';
import styles from '@/styles/management/ManagementMinutesList.module.css';

type ActiveDialog = { type: 'GENERATE' | 'PROCESSING'; meetingId: string } | null;

const INITIAL_FILTERS: MinutesListFilters = {
  search: '',
  status: 'ALL',
  time: 'ALL',
  role: 'ALL',
};

const EMPTY_SUMMARY: MinutesSummary = {
  totalMeetings: 0,
  notGenerated: 0,
  processing: 0,
  available: 0,
};

export function MinutesListPage() {
  const router = useRouter();
  const requestSequence = useRef(0);
  const [meetings, setMeetings] = useState<MeetingMinutesListItem[]>([]);
  const [summary, setSummary] = useState<MinutesSummary>(EMPTY_SUMMARY);
  const [filters, setFilters] = useState<MinutesListFilters>(INITIAL_FILTERS);
  const [debouncedFilters, setDebouncedFilters] = useState<MinutesListFilters>(INITIAL_FILTERS);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(5);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [activeDialog, setActiveDialog] = useState<ActiveDialog>(null);

  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(page, totalPages);
  const dialogMeeting = activeDialog
    ? (meetings.find((item) => item.meetingId === activeDialog.meetingId) ?? null)
    : null;

  const canManageMeeting = (meeting: MeetingMinutesListItem) => Boolean(meeting.canManage);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedFilters(filters), 300);
    return () => window.clearTimeout(timer);
  }, [filters]);

  useEffect(() => {
    let mounted = true;

    async function loadSummary() {
      try {
        setSummary(await getMinutesSummary());
      } catch (error) {
        if (!mounted) return;
        toast.error(error instanceof Error ? error.message : 'Không thể tải tổng quan biên bản.');
      }
    }

    void loadSummary();
    return () => {
      mounted = false;
    };
  }, []);

  useEffect(() => {
    const sequence = requestSequence.current + 1;
    requestSequence.current = sequence;
    setLoading(true);

    async function loadMinutes() {
      try {
        const response = await getMinutes({
          filters: debouncedFilters,
          page: safePage,
          size: pageSize,
        });
        if (requestSequence.current !== sequence) return;
        setMeetings(response.content);
        setTotal(response.totalElements);
      } catch (error) {
        if (requestSequence.current !== sequence) return;
        toast.error(error instanceof Error ? error.message : 'Không thể tải danh sách biên bản.');
      } finally {
        if (requestSequence.current === sequence) setLoading(false);
      }
    }

    void loadMinutes();
  }, [debouncedFilters, pageSize, safePage]);

  function updateFilters(nextFilters: MinutesListFilters) {
    setFilters(nextFilters);
    setPage(1);
  }

  async function reloadAfterAction() {
    const [summaryResponse, minutesResponse] = await Promise.all([
      getMinutesSummary(),
      getMinutes({ filters: debouncedFilters, page: safePage, size: pageSize }),
    ]);
    setSummary(summaryResponse);
    setMeetings(minutesResponse.content);
    setTotal(minutesResponse.totalElements);
  }

  function handleRefresh() {
    void reloadAfterAction().catch((error: unknown) => {
      toast.error(error instanceof Error ? error.message : 'Không thể làm mới danh sách biên bản.');
    });
  }

  function handleAction(meeting: MeetingMinutesListItem, action: MinutesTableAction) {
    if (action === 'DOCUMENTS') {
      router.push(`/management/minutes/${meeting.meetingId}?tab=details&section=documents`);
      return;
    }
    if (action === 'DETAILS') {
      router.push(`/management/minutes/${meeting.meetingId}?tab=details`);
      return;
    }
    if (action === 'OPEN_MEETING') {
      router.push(`/management/meetings/${meeting.meetingId}`);
      return;
    }
    if (!canManageMeeting(meeting) || meeting.status === 'DRAFT' || meeting.status === 'FINAL') {
      router.push(`/management/minutes/${meeting.meetingId}`);
      return;
    }
    if (meeting.status === 'PROCESSING') {
      setActiveDialog({ type: 'PROCESSING', meetingId: meeting.meetingId });
      return;
    }
    if (meeting.status === 'FAILED') {
      void retryMinutes(meeting.meetingId)
        .then(async () => {
          toast.success('Đã gửi yêu cầu thử lại tạo biên bản.');
          await reloadAfterAction();
          setActiveDialog({ type: 'PROCESSING', meetingId: meeting.meetingId });
        })
        .catch((error: unknown) => {
          toast.error(error instanceof Error ? error.message : 'Không thể thử lại tạo biên bản.');
        });
      return;
    }
    setActiveDialog({ type: 'GENERATE', meetingId: meeting.meetingId });
  }

  async function startProcessing(meetingId: string) {
    try {
      await generateMinutes(meetingId);
      toast.success('Đã gửi yêu cầu tạo biên bản.');
      await reloadAfterAction();
      setActiveDialog({ type: 'PROCESSING', meetingId });
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Không thể tạo biên bản.');
    }
  }

  return (
    <div className={styles.page}>
      <section className={styles.hero} aria-labelledby="minutes-list-title">
        <div className={styles.heroOverlay} />
        <div className={styles.heroContent}>
          <nav className={styles.breadcrumb} aria-label="Breadcrumb">
            <span>
              <HomeBrandLink />
            </span>
            <span>/</span>
            <span>Biên bản cuộc họp</span>
          </nav>
          <h1 id="minutes-list-title">Biên bản cuộc họp</h1>
          <p>
            Lưu trữ, tra cứu và quản lý tất cả biên bản cuộc họp của CUSC.
            <br />
            Đảm bảo thông tin được ghi nhận đầy đủ, chính xác và dễ dàng truy xuất.
          </p>
        </div>
        <div className={styles.heroTagline}>
          <span>Biến thảo luận</span>
          <span>thành giá trị bền vững</span>
          <i aria-hidden="true" />
        </div>
      </section>

      <MinutesSummaryCards summary={summary} />

      <section className={styles.listPanel}>
        <MinutesFilters filters={filters} onChange={updateFilters} onRefresh={handleRefresh} />

        {meetings.length > 0 ? (
          <MinutesTable
            meetings={meetings}
            total={total}
            page={safePage}
            pageSize={pageSize}
            onPageChange={setPage}
            onPageSizeChange={(size) => {
              setPageSize(size);
              setPage(1);
            }}
            onAction={handleAction}
            canManageMeeting={canManageMeeting}
          />
        ) : (
          <section className={styles.emptyState}>
            <span aria-hidden="true">
              <svg viewBox="0 0 24 24">
                <path d="M6 3.5h8l4 4v13H6z" />
                <path d="M14 3.5v4h4" />
                <path d="M9 12h6" />
                <path d="M9 16h5" />
              </svg>
            </span>
            <h3>
              {loading
                ? 'Đang tải danh sách biên bản...'
                : total === 0
                  ? 'Chưa có cuộc họp nào có thể tạo biên bản.'
                  : 'Không tìm thấy cuộc họp phù hợp.'}
            </h3>
            <p>
              {loading
                ? 'Vui lòng chờ trong giây lát.'
                : 'Hãy thay đổi từ khóa hoặc bộ lọc để xem kết quả khác.'}
            </p>
          </section>
        )}
      </section>

      {activeDialog?.type === 'GENERATE' && dialogMeeting ? (
        <GenerateMinutesDialog
          meeting={dialogMeeting}
          onClose={() => setActiveDialog(null)}
          onConfirm={() => void startProcessing(dialogMeeting.meetingId)}
        />
      ) : null}
      {activeDialog?.type === 'PROCESSING' && dialogMeeting ? (
        <ProcessingMinutesDialog
          meeting={dialogMeeting}
          onClose={() => setActiveDialog(null)}
          onDraftReady={() => {
            setActiveDialog(null);
            router.push(`/management/minutes/${dialogMeeting.meetingId}`);
          }}
        />
      ) : null}
    </div>
  );
}
