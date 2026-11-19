import type { ManagementMeetingJoinInfo } from '@/types/management/join';
import type { ManagementMeetingPoint } from '@/types/management/meeting';

function storageKey(meetingId: string) {
  return `cusc-meeting-point:${meetingId}`;
}

export function findSelectedMeetingPoint(
  meeting: ManagementMeetingJoinInfo,
  pointId: number | null,
): ManagementMeetingPoint | null {
  if (
    meeting.creationMode !== 'POINT' ||
    !Number.isSafeInteger(pointId) ||
    !pointId ||
    pointId < 1
  ) {
    return null;
  }
  return meeting.points?.find((point) => point.id === pointId) ?? null;
}

export function readMeetingPointSelection(meetingId: string): number | null {
  try {
    const value = window.sessionStorage.getItem(storageKey(meetingId));
    if (!value || !/^\d+$/.test(value)) return null;
    const id = Number(value);
    return Number.isSafeInteger(id) && id > 0 ? id : null;
  } catch {
    // Joining still works when the browser denies session storage.
    return null;
  }
}

export function storeMeetingPointSelection(meetingId: string, pointId: number | null) {
  try {
    if (pointId == null) window.sessionStorage.removeItem(storageKey(meetingId));
    else window.sessionStorage.setItem(storageKey(meetingId), String(pointId));
  } catch {
    // Storage is a convenience only; the selected ID is validated by the server.
  }
}
