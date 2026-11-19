import type { ManagementMeetingParticipant } from '@/types/management/meeting';

export const MAX_MEETING_POINTS = 100;
export const MAX_MEETING_POINT_NAME_LENGTH = 150;

export function parseMeetingPointNames(value: string): string[] {
  return value
    .split(/\r?\n/)
    .map((name) => name.trim())
    .filter(Boolean);
}

export function validateMeetingPointNames(names: string[]): string | null {
  if (names.length === 0) return 'Vui lòng nhập ít nhất một điểm cầu.';
  if (names.length > MAX_MEETING_POINTS) return 'Cuộc họp chỉ được có tối đa 100 điểm cầu.';
  const seen = new Set<string>();
  for (const rawName of names) {
    const name = rawName.trim();
    if (!name) return 'Tên điểm cầu không được để trống.';
    if (name.length > MAX_MEETING_POINT_NAME_LENGTH) {
      return 'Tên mỗi điểm cầu không được vượt quá 150 ký tự.';
    }
    if (/[\u0000-\u001f\u007f-\u009f]/.test(name)) {
      return 'Tên điểm cầu chứa ký tự không hợp lệ.';
    }
    const key = name.toLowerCase();
    if (seen.has(key)) return `Tên điểm cầu bị trùng: ${name}.`;
    seen.add(key);
  }
  return null;
}

export function validatePointMeetingParticipantRoles(
  participants: ManagementMeetingParticipant[],
): string | null {
  if (participants.filter((participant) => participant.role === 'CHAIRMAN').length > 1) {
    return 'Cuộc họp chỉ được có một chủ tọa.';
  }
  if (participants.filter((participant) => participant.role === 'SECRETARY').length > 1) {
    return 'Cuộc họp chỉ được có một thư ký.';
  }
  return null;
}
