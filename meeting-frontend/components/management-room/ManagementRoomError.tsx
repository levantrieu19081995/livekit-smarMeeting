import Link from 'next/link';
import styles from '@/styles/management/ManagementRoom.module.css';

interface ManagementRoomErrorProps {
  title?: string;
  message: string;
  meetingId: string;
  onRetry?: () => void;
}

export function ManagementRoomError({
  title = 'Không thể vào cuộc họp',
  message,
  onRetry,
}: ManagementRoomErrorProps) {
  return (
    <div className={styles.errorPage}>
      <div className={styles.errorCard}>
        <h2 className={styles.errorTitle}>{title}</h2>
        <p className={styles.errorText}>{message}</p>
        <div className={styles.errorActions}>
          {onRetry ? (
            <button type="button" className={styles.backLink} onClick={onRetry}>
              Thử lại
            </button>
          ) : null}
          <Link href="/" className={styles.backLink}>
            Về Trang chủ
          </Link>
        </div>
      </div>
    </div>
  );
}
