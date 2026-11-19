import {
  SPEAKING_STATUS_LABELS,
  type SpeakingAction,
  type SpeakingRequest,
} from '@/types/management/speaking';
import styles from '@/styles/management/Speaking.module.css';

function time(value: string | null) {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '—';
  return new Intl.DateTimeFormat('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    timeZone: 'Asia/Ho_Chi_Minh',
  }).format(parsed);
}

export function SpeakingTimeline({
  rows,
  readOnly,
  busy,
  onAction,
}: {
  rows: SpeakingRequest[];
  readOnly: boolean;
  busy: boolean;
  onAction: (row: SpeakingRequest, action: SpeakingAction) => void;
}) {
  return (
    <ol className={styles.timeline}>
      {rows.map((row) => (
        <li key={row.id} className={styles.card}>
          <div className={styles.cardHeader}>
            <span
              className={`${styles.badge} ${row.status === 'SPEAKING' ? styles.speaking : row.status === 'WAITING' ? styles.waiting : ''}`}
            >
              {SPEAKING_STATUS_LABELS[row.status]}
            </span>
            {row.isOwn ? <span className={styles.help}>Yêu cầu của bạn</span> : null}
          </div>
          <h4>{row.speakerName}</h4>
          <p className={styles.point}>{row.pointName}</p>
          {row.requestedByDisplayName !== row.speakerName ? (
            <p className={styles.help}>Đại diện gửi: {row.requestedByDisplayName}</p>
          ) : null}
          <dl className={styles.times}>
            <div>
              <dt>Xin phát biểu</dt>
              <dd>
                <time dateTime={row.requestedAt}>{time(row.requestedAt)}</time>
              </dd>
            </div>
            {row.approvedAt ? (
              <div>
                <dt>Được duyệt</dt>
                <dd>
                  <time dateTime={row.approvedAt}>{time(row.approvedAt)}</time>
                  {row.approvedByDisplayName ? (
                    <span className={styles.by}> · {row.approvedByDisplayName}</span>
                  ) : null}
                </dd>
              </div>
            ) : null}
            {row.finishedAt ? (
              <div>
                <dt>{row.status === 'CANCELLED' ? 'Đã hủy' : 'Kết thúc'}</dt>
                <dd>
                  <time dateTime={row.finishedAt}>{time(row.finishedAt)}</time>
                  {row.finishedByDisplayName ? (
                    <span className={styles.by}> · {row.finishedByDisplayName}</span>
                  ) : null}
                </dd>
              </div>
            ) : null}
          </dl>
          {!readOnly && (row.canApprove || row.canFinish || row.canCancel) ? (
            <div className={styles.actions}>
              {row.status === 'WAITING' && row.canApprove ? (
                <button
                  type="button"
                  className={styles.primary}
                  disabled={busy}
                  onClick={() => onAction(row, 'approve')}
                >
                  Duyệt
                </button>
              ) : null}
              {row.status === 'SPEAKING' && row.canFinish ? (
                <button type="button" disabled={busy} onClick={() => onAction(row, 'finish')}>
                  Kết thúc
                </button>
              ) : null}
              {row.status === 'WAITING' && row.canCancel ? (
                <button type="button" disabled={busy} onClick={() => onAction(row, 'cancel')}>
                  Hủy yêu cầu
                </button>
              ) : null}
            </div>
          ) : null}
        </li>
      ))}
    </ol>
  );
}
