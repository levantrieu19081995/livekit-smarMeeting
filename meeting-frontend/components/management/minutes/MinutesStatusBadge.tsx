import type { MinutesStatus } from '@/types/management/minutes';
import styles from '@/styles/management/Minutes.module.css';

const STATUS_LABELS: Record<MinutesStatus, string> = {
  NOT_GENERATED: 'Chưa tạo',
  PROCESSING: 'Đang xử lý',
  DRAFT: 'Bản nháp',
  FINAL: 'Hoàn tất',
  FAILED: 'Lỗi xử lý',
};

export function MinutesStatusBadge({ status }: { status: MinutesStatus }) {
  return (
    <span className={`${styles.statusBadge} ${styles[`status${status}`]}`}>
      {STATUS_LABELS[status]}
    </span>
  );
}
