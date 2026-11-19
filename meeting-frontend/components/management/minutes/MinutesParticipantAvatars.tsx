import type { MinutesParticipant } from '@/types/management/minutes';
import styles from '@/styles/management/ManagementMinutesList.module.css';

interface MinutesParticipantAvatarsProps {
  participants: MinutesParticipant[];
  total: number;
  visibleCount?: number;
}

export function MinutesParticipantAvatars({
  participants,
  total,
  visibleCount = 3,
}: MinutesParticipantAvatarsProps) {
  const visible = participants.slice(0, visibleCount);
  const remaining = Math.max(0, total - visible.length);

  return (
    <div className={styles.avatarStack} aria-label={`${total} người tham dự`}>
      {visible.map((participant, index) => (
        <span
          key={participant.id}
          className={`${styles.participantAvatar} ${styles[`avatarTone${index % 4}`]}`}
          title={participant.name}
        >
          {participant.initials}
        </span>
      ))}
      {remaining > 0 ? <span className={styles.avatarMore}>+{remaining}</span> : null}
    </div>
  );
}
