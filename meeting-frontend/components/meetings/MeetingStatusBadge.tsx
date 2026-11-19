import {
  MANAGEMENT_MEETING_ACCESS_TYPE_LABELS,
  MANAGEMENT_MEETING_ROLE_LABELS,
  MANAGEMENT_MEETING_STATUS_LABELS,
  MANAGEMENT_PARTICIPANT_STATUS_LABELS,
} from '@/types/management/meeting';
import type {
  ManagementMeetingAccessType,
  ManagementMeetingRole,
  ManagementMeetingStatus,
  ManagementParticipantStatus,
} from '@/types/management/meeting';
import styles from '@/styles/management/MeetingPages.module.css';

export function MeetingStatusBadge({ status }: { status: ManagementMeetingStatus }) {
  return (
    <span className={`${styles.badge} ${styles[`status${status}`]}`}>
      {MANAGEMENT_MEETING_STATUS_LABELS[status]}
    </span>
  );
}

export function MeetingAccessBadge({ accessType }: { accessType: ManagementMeetingAccessType }) {
  return (
    <span className={`${styles.badge} ${styles[`access${accessType}`]}`}>
      {MANAGEMENT_MEETING_ACCESS_TYPE_LABELS[accessType]}
    </span>
  );
}

export function MeetingRoleBadge({ role }: { role: ManagementMeetingRole }) {
  return (
    <span className={`${styles.badge} ${styles.roleBadge} ${styles[`role${role}`]}`}>
      {MANAGEMENT_MEETING_ROLE_LABELS[role]}
    </span>
  );
}

export function MeetingJoinStatusBadge({
  joinStatus,
}: {
  joinStatus: ManagementParticipantStatus;
}) {
  return (
    <span className={`${styles.badge} ${styles[`join${joinStatus}`]}`}>
      {MANAGEMENT_PARTICIPANT_STATUS_LABELS[joinStatus]}
    </span>
  );
}
