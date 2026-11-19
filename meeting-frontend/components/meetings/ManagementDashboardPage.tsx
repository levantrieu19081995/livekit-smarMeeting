'use client';

import Link from 'next/link';
import { useEffect, useMemo, useState } from 'react';
import { ManagementPageHero } from '@/components/management/ManagementPageHero';
import { useManagementAuth } from '@/hooks/useManagementAuth';
import { MeetingCreateMenu } from '@/components/meetings/MeetingCreateMenu';
import { listManagementMeetings } from '@/services/management/managementMeetingApi';
import type { ManagementMeeting, ManagementMeetingStatus } from '@/types/management/meeting';
import styles from '@/styles/management/ManagementDashboard.module.css';

type DashboardIcon =
  | 'calendar'
  | 'clock'
  | 'broadcast'
  | 'check'
  | 'plus'
  | 'bolt'
  | 'minutes'
  | 'users'
  | 'settings'
  | 'dots'
  | 'arrow'
  | 'room';

const STATUS_LABELS: Record<ManagementMeetingStatus, string> = {
  UPCOMING: 'Sắp diễn ra',
  IN_PROGRESS: 'Đang diễn ra',
  COMPLETED: 'Đã kết thúc',
  CANCELLED: 'Đã hủy',
};

function Icon({ name }: { name: DashboardIcon }) {
  switch (name) {
    case 'calendar':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M7 2h2v3H7V2Zm8 0h2v3h-2V2ZM4 6h16a1 1 0 0 1 1 1v13a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a1 1 0 0 1 1-1Zm0 4v10h16V10H4Zm3 3h4v4H7v-4Z" />
        </svg>
      );
    case 'clock':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20Zm1 5h-2v6l5 3 .98-1.74L13 11.9V7Z" />
        </svg>
      );
    case 'broadcast':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 9a3 3 0 1 1 0 6 3 3 0 0 1 0-6Zm-5.66-.66 1.42 1.42A5.97 5.97 0 0 0 6 14c0 1.65.67 3.15 1.76 4.24l-1.42 1.42A7.97 7.97 0 0 1 4 14c0-2.21.9-4.21 2.34-5.66Zm11.32 0A7.97 7.97 0 0 1 20 14c0 2.21-.9 4.21-2.34 5.66l-1.42-1.42A5.97 5.97 0 0 0 18 14c0-1.65-.67-3.15-1.76-4.24l1.42-1.42ZM3.51 5.51l1.42 1.42A9.96 9.96 0 0 0 2 14c0 2.76 1.12 5.26 2.93 7.07l-1.42 1.42A11.96 11.96 0 0 1 0 14c0-3.31 1.34-6.31 3.51-8.49Zm16.98 0A11.96 11.96 0 0 1 24 14c0 3.31-1.34 6.31-3.51 8.49l-1.42-1.42A9.96 9.96 0 0 0 22 14c0-2.76-1.12-5.26-2.93-7.07l1.42-1.42Z" />
        </svg>
      );
    case 'check':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20Zm-1.1 13.8 6.35-6.36-1.42-1.41-4.93 4.94-2.12-2.12-1.41 1.41 3.53 3.54Z" />
        </svg>
      );
    case 'plus':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M11 4h2v7h7v2h-7v7h-2v-7H4v-2h7V4Z" />
        </svg>
      );
    case 'bolt':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="m13 2-9 12h7l-1 8 10-13h-7l1-7Z" />
        </svg>
      );
    case 'minutes':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M6 2h9l5 5v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Zm8 1.5V8h4.5L14 3.5ZM8 11h8v1.5H8V11Zm0 4h8v1.5H8V15Z" />
        </svg>
      );
    case 'users':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M16 11a4 4 0 1 0-4-4 4 4 0 0 0 4 4Zm-8 1a3 3 0 1 0-3-3 3 3 0 0 0 3 3Zm0 2c-2.67 0-8 1.34-8 4v2h10v-2c0-1.25.52-2.31 1.37-3.17C10.28 14.29 9 14 8 14Zm8 0c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4Z" />
        </svg>
      );
    case 'settings':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="m19.43 12.98 1.34-1.03-1.28-2.2-1.65.33a6.8 6.8 0 0 0-1.15-.67l-.25-1.66h-2.56l-.25 1.66c-.4.16-.78.38-1.15.67l-1.65-.33-1.28 2.2 1.34 1.03a6.7 6.7 0 0 0 0 1.35l-1.34 1.03 1.28 2.2 1.65-.33c.36.29.75.51 1.15.67l.25 1.66h2.56l.25-1.66c.4-.16.79-.38 1.15-.67l1.65.33 1.28-2.2-1.34-1.03c.05-.22.07-.45.07-.68s-.02-.46-.07-.68ZM15 12.3a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z" />
        </svg>
      );
    case 'dots':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M6 10.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3Zm6 0a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3Zm6 0a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3Z" />
        </svg>
      );
    case 'arrow':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="m14 5 7 7-7 7-1.42-1.42L17.17 13H3v-2h14.17l-4.59-4.58L14 5Z" />
        </svg>
      );
    case 'room':
      return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M4 5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v14h2v2H2v-2h2V5Zm2 0v14h12V5H6Zm7 6h3v2h-3v-2Z" />
        </svg>
      );
  }
}

function formatDateTime(value: string | null) {
  if (!value) return 'Chưa bắt đầu';
  return new Intl.DateTimeFormat('vi-VN', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(value));
}

function formatTime(value: string | null) {
  if (!value) return '—';
  return new Intl.DateTimeFormat('vi-VN', {
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function formatTodayHeading() {
  return new Intl.DateTimeFormat('vi-VN', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(new Date());
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

  if (hours > 0 && remainingMinutes > 0) return `${hours}h ${remainingMinutes}m`;
  if (hours > 0) return `${hours}h`;
  return `${remainingMinutes}m`;
}

function isToday(value: string | null) {
  if (!value) return false;
  const date = new Date(value);
  const today = new Date();

  return (
    date.getFullYear() === today.getFullYear() &&
    date.getMonth() === today.getMonth() &&
    date.getDate() === today.getDate()
  );
}

function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: { label: string; href: string };
}) {
  return (
    <div className={styles.emptyState}>
      <div className={styles.emptyStateIcon} aria-hidden="true">
        <Icon name="calendar" />
      </div>
      <strong>{title}</strong>
      <p>{description}</p>
      {action ? (
        <Link href={action.href} className={styles.emptyAction}>
          <Icon name="plus" />
          <span>{action.label}</span>
        </Link>
      ) : null}
    </div>
  );
}

function PanelHeader({
  icon,
  title,
  linkLabel,
}: {
  icon: DashboardIcon;
  title: string;
  linkLabel: string;
}) {
  return (
    <div className={styles.panelHeader}>
      <div className={styles.panelTitleGroup}>
        <span className={styles.panelTitleIcon}>
          <Icon name={icon} />
        </span>
        <h2>{title}</h2>
      </div>
      <Link href="/management/meetings" className={styles.panelLink}>
        <span>{linkLabel}</span>
        <Icon name="arrow" />
      </Link>
    </div>
  );
}

function StatusPill({ status }: { status: ManagementMeetingStatus }) {
  return (
    <span className={`${styles.statusPill} ${styles[`status${status}`]}`}>
      {STATUS_LABELS[status]}
    </span>
  );
}

function UpcomingMeetingItem({ meeting }: { meeting: ManagementMeeting }) {
  return (
    <Link href={`/management/meetings/${meeting.id}`} className={styles.upcomingItem}>
      <span className={styles.smallIconTile}>
        <Icon name="calendar" />
      </span>
      <span className={styles.meetingText}>
        <strong>{meeting.title}</strong>
        <span>{`${formatDateTime(meeting.startTime)} • ${meeting.participants.length} người tham gia`}</span>
      </span>
      <StatusPill status={meeting.status} />
    </Link>
  );
}

function RecentMeetingItem({ meeting }: { meeting: ManagementMeeting }) {
  return (
    <div className={styles.recentItem}>
      <Link href={`/management/meetings/${meeting.id}`} className={styles.recentMain}>
        <span className={styles.smallIconTile}>
          <Icon name="room" />
        </span>
        <span className={styles.meetingText}>
          <strong>{meeting.title}</strong>
          <span>{`${meeting.roomName || meeting.meetingCode || 'Phòng họp'} • ${formatDateTime(meeting.startTime)}`}</span>
        </span>
      </Link>
      <StatusPill status={meeting.status} />
      <Link href={`/join/${meeting.id}`} className={styles.joinButton}>
        Tham gia
      </Link>
      <details className={styles.kebab}>
        <summary aria-label="Tác vụ cuộc họp">
          <Icon name="dots" />
        </summary>
        <div className={styles.kebabMenu}>
          <Link href={`/management/meetings/${meeting.id}`}>Xem chi tiết</Link>
        </div>
      </details>
    </div>
  );
}

function TodayItem({ meeting }: { meeting: ManagementMeeting }) {
  return (
    <div className={styles.todayItem}>
      <time className={styles.todayTime} dateTime={meeting.startTime ?? undefined}>
        {formatTime(meeting.startTime)}
      </time>
      <span className={`${styles.timelineDot} ${styles[`dot${meeting.status}`]}`} />
      <div className={styles.todayContent}>
        <strong>{meeting.title}</strong>
        <span>Phòng: {meeting.roomName}</span>
      </div>
      <span className={styles.duration}>{formatDuration(meeting.startTime, meeting.endTime)}</span>
    </div>
  );
}

export function ManagementDashboardPage() {
  const { user } = useManagementAuth();
  const [meetings, setMeetings] = useState<ManagementMeeting[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    async function loadMeetings() {
      try {
        setLoading(true);
        setMeetings(await listManagementMeetings({ size: 20 }));
        setError(null);
      } catch (loadError) {
        setError(
          loadError instanceof Error ? loadError.message : 'Không thể tải dashboard cuộc họp.',
        );
      } finally {
        setLoading(false);
      }
    }

    void loadMeetings();
  }, []);

  const stats = useMemo(() => {
    return meetings.reduce(
      (summary, meeting) => {
        summary.total += 1;
        if (meeting.status === 'UPCOMING') summary.upcoming += 1;
        if (meeting.status === 'IN_PROGRESS') summary.inProgress += 1;
        if (meeting.status === 'COMPLETED') summary.completed += 1;
        return summary;
      },
      { total: 0, upcoming: 0, inProgress: 0, completed: 0 },
    );
  }, [meetings]);

  const upcomingMeetings = useMemo(
    () =>
      meetings
        .filter((meeting) => meeting.status === 'UPCOMING')
        .sort(
          (left, right) =>
            new Date(left.startTime ?? 0).getTime() - new Date(right.startTime ?? 0).getTime(),
        )
        .slice(0, 4),
    [meetings],
  );

  const recentMeetings = useMemo(
    () =>
      meetings
        .filter((meeting) => meeting.status !== 'UPCOMING')
        .sort(
          (left, right) =>
            new Date(right.startTime ?? 0).getTime() - new Date(left.startTime ?? 0).getTime(),
        )
        .slice(0, 4),
    [meetings],
  );

  const todayMeetings = useMemo(
    () =>
      meetings
        .filter((meeting) => isToday(meeting.startTime))
        .sort(
          (left, right) =>
            new Date(left.startTime ?? 0).getTime() - new Date(right.startTime ?? 0).getTime(),
        )
        .slice(0, 3),
    [meetings],
  );

  const kpis = [
    {
      label: 'Tổng số cuộc họp',
      value: stats.total,
      icon: 'calendar' as const,
      tone: styles.kpiBlue,
    },
    {
      label: 'Cuộc họp sắp diễn ra',
      value: stats.upcoming,
      icon: 'clock' as const,
      tone: styles.kpiPurple,
    },
    {
      label: 'Đang diễn ra',
      value: stats.inProgress,
      icon: 'broadcast' as const,
      tone: styles.kpiGreen,
    },
    {
      label: 'Đã kết thúc',
      value: stats.completed,
      icon: 'check' as const,
      tone: styles.kpiSlate,
    },
  ];

  const quickActions = [
    {
      title: 'Tạo cuộc họp',
      description: 'Bắt đầu cuộc họp mới',
      href: '/management/meetings/create',
      icon: 'plus' as const,
      tone: styles.quickBlue,
      adminOnly: false,
    },
    {
      title: 'Quản lý biên bản',
      description: 'Xem và chỉnh sửa biên bản',
      href: '/management/minutes',
      icon: 'minutes' as const,
      tone: styles.quickPurple,
      adminOnly: false,
    },
    {
      title: 'Quản lý người dùng',
      description: 'Quản lý tài khoản và quyền',
      href: '/management/users',
      icon: 'users' as const,
      tone: styles.quickGreen,
      adminOnly: true,
    },
    {
      title: 'Cài đặt hệ thống',
      description: 'Tùy chỉnh cấu hình',
      href: '/management/settings',
      icon: 'settings' as const,
      tone: styles.quickOrange,
      adminOnly: true,
    },
  ].filter((action) => !action.adminOnly || user?.systemRole === 'ADMIN');

  if (loading) {
    return <div className={styles.stateCard}>Đang tải dữ liệu dashboard...</div>;
  }

  if (error) {
    return <div className={styles.stateCard}>Không thể tải dashboard: {error}</div>;
  }

  return (
    <div className={styles.dashboard}>
      <ManagementPageHero
        eyebrow="CHÀO MỪNG TRỞ LẠI"
        title="Quản lý cuộc họp dễ dàng hơn"
        subtitle="Tạo, theo dõi và quản lý tất cả cuộc họp trực tuyến của CUSC trên một nền tảng duy nhất, an toàn và hiệu quả."
        tagline={{
          line1: 'Kết nối tri thức',
          line2: 'Tạo giá trị mới',
        }}
        actionsContent={
          <MeetingCreateMenu
            label="Tạo cuộc họp mới"
            onCreated={(meeting) => setMeetings((current) => [meeting, ...current])}
          />
        }
        actions={[
          {
            label: 'Xem lịch cuộc họp',
            href: '/management/meetings',
            icon: <Icon name="calendar" />,
            variant: 'secondary',
          },
        ]}
      />

      <section className={styles.kpiGrid} aria-label="Thống kê cuộc họp">
        {kpis.map((kpi) => (
          <article key={kpi.label} className={styles.kpiCard}>
            <span className={`${styles.kpiIconTile} ${kpi.tone}`}>
              <Icon name={kpi.icon} />
            </span>
            <div className={styles.kpiContent}>
              <span className={styles.kpiLabel}>{kpi.label}</span>
              <strong className={styles.kpiValue}>{kpi.value}</strong>
              <span className={styles.kpiHelper}>Theo dữ liệu hiện tại</span>
            </div>
          </article>
        ))}
      </section>

      <section className={styles.mainGrid} aria-label="Tổng quan lịch họp">
        <article className={styles.panel}>
          <PanelHeader icon="calendar" title="Cuộc họp sắp tới" linkLabel="Xem tất cả" />
          <div className={styles.panelBody}>
            {upcomingMeetings.length > 0 ? (
              <div className={styles.upcomingList}>
                {upcomingMeetings.map((meeting) => (
                  <UpcomingMeetingItem key={meeting.id} meeting={meeting} />
                ))}
              </div>
            ) : (
              <EmptyState
                title="Không có cuộc họp sắp diễn ra"
                description="Hiện tại chưa có cuộc họp nào sắp tới. Hãy tạo cuộc họp mới để bắt đầu."
                action={{ label: 'Tạo cuộc họp', href: '/management/meetings/create' }}
              />
            )}
          </div>
        </article>

        <article className={styles.panel}>
          <PanelHeader icon="clock" title="Cuộc họp gần đây" linkLabel="Xem tất cả" />
          <div className={styles.panelBody}>
            {recentMeetings.length > 0 ? (
              <div className={styles.recentList}>
                {recentMeetings.map((meeting) => (
                  <RecentMeetingItem key={meeting.id} meeting={meeting} />
                ))}
              </div>
            ) : (
              <EmptyState
                title="Chưa có dữ liệu cuộc họp gần đây"
                description="Các cuộc họp đã diễn ra hoặc đang hoạt động sẽ xuất hiện tại đây."
              />
            )}
          </div>
        </article>

        <article className={styles.panel}>
          <PanelHeader icon="clock" title="Lịch hôm nay" linkLabel="Xem lịch" />
          <div className={styles.todayBody}>
            <h3>{formatTodayHeading()}</h3>
            {todayMeetings.length > 0 ? (
              <div className={styles.todayList}>
                {todayMeetings.map((meeting) => (
                  <TodayItem key={meeting.id} meeting={meeting} />
                ))}
              </div>
            ) : (
              <div className={styles.todayEmpty}>
                <span>Không có cuộc họp hôm nay.</span>
              </div>
            )}
            <Link href="/management/meetings" className={styles.fullScheduleButton}>
              Xem lịch đầy đủ
            </Link>
          </div>
        </article>
      </section>

      <section className={styles.quickPanel} aria-labelledby="quick-actions-title">
        <div className={styles.quickHeader}>
          <span className={styles.quickHeaderIcon}>
            <Icon name="bolt" />
          </span>
          <div>
            <h2 id="quick-actions-title">Thao tác nhanh</h2>
            <p>Truy cập nhanh các chức năng thường dùng</p>
          </div>
        </div>
        <div className={styles.quickGrid}>
          {quickActions.map((action) => (
            <Link key={action.href} href={action.href} className={styles.quickAction}>
              <span className={`${styles.quickIconTile} ${action.tone}`}>
                <Icon name={action.icon} />
              </span>
              <span className={styles.quickText}>
                <strong>{action.title}</strong>
                <span>{action.description}</span>
              </span>
            </Link>
          ))}
        </div>
      </section>
    </div>
  );
}
