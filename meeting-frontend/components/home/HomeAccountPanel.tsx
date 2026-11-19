'use client';

import Link from 'next/link';
import { useManagementAuth } from '@/hooks/useManagementAuth';
import styles from '@/styles/Home.module.css';

function getInitials(fullName: string) {
  const words = fullName.trim().split(/\s+/).filter(Boolean);

  if (words.length === 0) {
    return 'TK';
  }

  return words
    .slice(-2)
    .map((word) => word[0])
    .join('')
    .toUpperCase();
}

export function HomeAccountPanel() {
  const { loading, authenticated, user } = useManagementAuth();
  const managementHref = user?.systemRole === 'USER' ? '/management/minutes' : '/management';

  if (loading) {
    return <div className={styles.accountSkeleton} aria-label="Đang kiểm tra đăng nhập" />;
  }

  if (!authenticated || !user) {
    return (
      <div className={styles.authActions}>
        <Link className={styles.primaryAction} href="/auth?returnTo=%2Fmanagement">
          Đăng nhập
        </Link>
      </div>
    );
  }

  return (
    <div className={styles.accountPanel}>
      <Link className={styles.accountLink} href={managementHref} aria-label="Mở trang quản lý">
        <span className={styles.accountAvatar}>{getInitials(user.fullName)}</span>

        <span className={styles.accountInfo}>
          <strong>{user.fullName}</strong>
        </span>
      </Link>
    </div>
  );
}
