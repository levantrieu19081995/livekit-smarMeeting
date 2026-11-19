import { managementFetchJson } from '@/services/management/httpClient';
import type {
  ManagementJoinRequest,
  ManagementJoinResponse,
  ManagementMeetingJoinInfo,
} from '@/types/management/join';

export async function getMeetingJoinInfo(meetingId: string): Promise<ManagementMeetingJoinInfo> {
  return managementFetchJson<ManagementMeetingJoinInfo>(
    `/api/meetings/${encodeURIComponent(meetingId)}/join-info`,
    {
      method: 'GET',
    },
  );
}

export async function joinManagementMeeting(
  meetingId: string,
  request: ManagementJoinRequest,
): Promise<ManagementJoinResponse> {
  return managementFetchJson<ManagementJoinResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/join`,
    {
      method: 'POST',
      body: JSON.stringify({
        participantType: request.participantType,
        guestId: request.guestId || null,
        displayName: request.displayName.trim(),
        ...(request.guestResumeToken ? { guestResumeToken: request.guestResumeToken } : {}),
        ...(request.pointId != null ? { pointId: request.pointId } : {}),
      }),
    },
  );
}
