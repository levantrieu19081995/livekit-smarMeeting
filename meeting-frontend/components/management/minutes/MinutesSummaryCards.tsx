import type { ReactNode } from 'react';
import type { MinutesSummary } from '@/types/management/minutes';
import styles from '@/styles/management/ManagementMinutesList.module.css';

type SummaryIcon = 'calendar' | 'document' | 'clock' | 'check';

function SummaryCardIcon({ icon }: { icon: SummaryIcon }) {
  const paths: Record<SummaryIcon, ReactNode> = {
    calendar: (
      <>
        <path d="M7 3v3" />
        <path d="M17 3v3" />
        <rect x="3.5" y="5" width="17" height="16" rx="2.5" />
        <path d="M3.5 9.5h17" />
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
    clock: (
      <>
        <circle cx="12" cy="12" r="8.5" />
        <path d="M12 7.5V12l3 2" />
      </>
    ),
    check: (
      <>
        <circle cx="12" cy="12" r="8.5" />
        <path d="m8.5 12 2.2 2.2 4.8-5" />
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
      {paths[icon]}
    </svg>
  );
}

export function MinutesSummaryCards({ summary }: { summary: MinutesSummary }) {
  const summaries = [
    {
      icon: 'calendar' as const,
      value: summary.totalMeetings,
      title: 'Tổng cuộc họp',
      description: 'Theo dữ liệu hiện tại',
      tone: styles.summaryIconBlue,
    },
    {
      icon: 'document' as const,
      value: summary.notGenerated,
      title: 'Chưa tạo biên bản',
      description: 'Theo dữ liệu hiện tại',
      tone: styles.summaryIconPurple,
    },
    {
      icon: 'clock' as const,
      value: summary.processing,
      title: 'Đang xử lý',
      description: 'Theo dữ liệu hiện tại',
      tone: styles.summaryIconOrange,
    },
    {
      icon: 'check' as const,
      value: summary.available,
      title: 'Đã có biên bản',
      description: 'Theo dữ liệu hiện tại',
      tone: styles.summaryIconGreen,
    },
  ];

  return (
    <section className={styles.summaryGrid} aria-label="Tổng quan biên bản">
      {summaries.map((summary) => (
        <article key={summary.title} className={styles.summaryCard}>
          <span className={`${styles.summaryIcon} ${summary.tone}`}>
            <SummaryCardIcon icon={summary.icon} />
          </span>
          <div className={styles.summaryContent}>
            <h3>{summary.title}</h3>
            <strong className={styles.summaryValue}>{summary.value}</strong>
            <p>{summary.description}</p>
          </div>
        </article>
      ))}
    </section>
  );
}
