'use client';

import { ManagementDashboardPage } from '@/components/meetings/ManagementDashboardPage';
import { useManagementAuth } from '@/hooks/useManagementAuth';
import styles from '@/styles/management/MeetingPages.module.css';

export default function ManagementPage() {
  const { loading, user } = useManagementAuth();
  if (loading || !user) {
    return <div className={styles.stateCard}>Dang tai khu vuc quan ly...</div>;
  }

  return <ManagementDashboardPage />;
}
