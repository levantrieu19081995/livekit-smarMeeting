import { HomeBrandLink } from '@/components/home/HomeBrandLink';
import type { ManagementCurrentUser, ManagementMeetingJoinInfo } from '@/types/management/join';
import styles from '@/styles/management/ManagementRoom.module.css';

interface ManagementRoomHeaderProps {
  meeting: ManagementMeetingJoinInfo;
  currentUser: ManagementCurrentUser;
}

export function ManagementRoomHeader({ meeting, currentUser }: ManagementRoomHeaderProps) {
  const title = meeting.title ?? 'Cuộc họp riêng tư';

  return (
    <header className={styles.header}>
      <div className={styles.headerMeta}>
        <p className={styles.eyebrow}>
          Phòng họp <HomeBrandLink />
        </p>
        <h1 className={styles.title}>{title}</h1>
      </div>

      <div className={styles.headerActions}>
        <div className={styles.userCard} title={currentUser.name}>
          <strong>{currentUser.name}</strong>
        </div>
      </div>
    </header>
  );
}
