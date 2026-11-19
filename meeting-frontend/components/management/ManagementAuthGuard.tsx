'use client';

import { useEffect } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useManagementAuth } from '@/hooks/useManagementAuth';
import styles from '@/styles/management/ManagementShell.module.css';

export function ManagementAuthGuard({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { loading, authenticated, sessionUnavailable, refreshSession } = useManagementAuth();

  useEffect(() => {
    if (!loading && !authenticated && !sessionUnavailable) {
      const returnTo = pathname.startsWith('/management') ? pathname : '/management';

      router.replace(`/auth/login?returnTo=${encodeURIComponent(returnTo)}`);
    }
  }, [authenticated, loading, sessionUnavailable, pathname, router]);

  if (loading) {
    return (
      <div className={styles.authGate} role="status" aria-live="polite">
        <div className={styles.authGateSpinner} />
        <strong>Đang kiểm tra đăng nhập...</strong>
      </div>
    );
  }

  if (sessionUnavailable && !authenticated) {
    return (
      <div className={styles.authGate} role="alert">
        <strong>Chưa thể xác nhận phiên đăng nhập.</strong>
        <button type="button" onClick={() => void refreshSession()}>
          Thử lại
        </button>
      </div>
    );
  }
  if (!authenticated) {
    return (
      <div className={styles.authGate} role="status" aria-live="polite">
        <div className={styles.authGateSpinner} />
        <strong>Đang chuyển đến trang đăng nhập...</strong>
      </div>
    );
  }

  return children;
}
