import type {
  ManagementMeetingAccessType,
  ManagementMeetingParticipant,
  ManagementMeetingRole,
} from '@/types/management/meeting';

export type MeetingParticipantMode = 'create' | 'edit';

const participantRoles: ManagementMeetingRole[] = ['CHAIRMAN', 'SECRETARY', 'MEMBER'];

export function getDefaultMeetingParticipantRole(
  _accessType: ManagementMeetingAccessType,
  assignedRoles: ManagementMeetingRole[],
  mode: MeetingParticipantMode,
): ManagementMeetingRole {
  if (!assignedRoles.includes('CHAIRMAN')) return 'CHAIRMAN';
  if (mode === 'create' && !assignedRoles.includes('SECRETARY')) return 'SECRETARY';
  return 'MEMBER';
}

function findOriginalParticipant(
  participant: ManagementMeetingParticipant,
  originalParticipants: ManagementMeetingParticipant[],
) {
  return originalParticipants.find((original) => {
    if (original.status === 'REMOVED') return false;
    if (participant.participantKey && original.participantKey) {
      return participant.participantKey === original.participantKey;
    }
    return participant.userId === original.userId;
  });
}

export function getMeetingParticipantRoleOptions(
  _accessType: ManagementMeetingAccessType,
  mode: MeetingParticipantMode,
  participant?: ManagementMeetingParticipant | null,
  originalParticipants: ManagementMeetingParticipant[] = [],
): ManagementMeetingRole[] {
  const roles = participantRoles;
  const original =
    mode === 'edit' && participant && findOriginalParticipant(participant, originalParticipants);
  return original && !roles.includes(original.role) ? [...roles, original.role] : roles;
}

export function validateMeetingParticipantRoles(
  accessType: ManagementMeetingAccessType,
  participants: ManagementMeetingParticipant[],
  mode: MeetingParticipantMode,
  originalParticipants: ManagementMeetingParticipant[] = [],
): string | null {
  if (participants.filter((participant) => participant.role === 'CHAIRMAN').length > 1) {
    return 'Cuộc họp chỉ được có một chủ tọa.';
  }
  if (
    mode === 'create' &&
    participants.filter((participant) => participant.role === 'SECRETARY').length > 1
  ) {
    return 'Cuộc họp chỉ được có một thư ký.';
  }
  if (
    accessType === 'PUBLIC' &&
    participants.some((participant) => {
      if (participantRoles.includes(participant.role)) return false;
      if (mode !== 'edit') return true;
      // Keep persisted attendees without offering GUEST for new invitations.
      const original = findOriginalParticipant(participant, originalParticipants);
      return original?.role !== participant.role;
    })
  ) {
    return mode === 'edit'
      ? 'Vui lòng chọn vai trò chủ tọa, thư ký hoặc thành viên. Khách đã tham gia được giữ nguyên vai trò.'
      : 'Vui lòng chọn vai trò chủ tọa, thư ký hoặc thành viên.';
  }
  return null;
}

export function validateMeetingParticipants(
  accessType: ManagementMeetingAccessType,
  participants: ManagementMeetingParticipant[],
  mode: MeetingParticipantMode,
  originalParticipants: ManagementMeetingParticipant[] = [],
): string | null {
  if (accessType === 'PRIVATE' && participants.length === 0) {
    return 'Vui lòng thêm ít nhất một người tham gia.';
  }
  if (!participants.some((participant) => participant.role === 'CHAIRMAN')) {
    return 'Vui lòng chọn một chủ tọa cho cuộc họp.';
  }
  return validateMeetingParticipantRoles(accessType, participants, mode, originalParticipants);
}
