import { managementFetchJson } from '@/services/management/httpClient';
import type {
  ManagementChatMessageResponse,
  ManagementChatHistoryPage,
  SaveManagementChatMessageRequest,
} from '@/types/management/chat';

const MEETING_PARTICIPANT_TOKEN_HEADER = 'X-Meeting-Participant-Token';

export async function saveManagementChatMessage(
  meetingId: string,
  chatWriteToken: string,
  request: SaveManagementChatMessageRequest,
  signal?: AbortSignal,
): Promise<ManagementChatMessageResponse> {
  return managementFetchJson<ManagementChatMessageResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/chat/messages`,
    {
      method: 'POST',
      signal,
      headers: {
        [MEETING_PARTICIPANT_TOKEN_HEADER]: chatWriteToken,
      },
      body: JSON.stringify({
        clientMessageId: request.clientMessageId,
        content: request.content.trim(),
      }),
    },
  );
}

export function getManagementChatHistory(
  meetingId: string,
  participantToken: string,
  afterId = 0,
  signal?: AbortSignal,
): Promise<ManagementChatHistoryPage> {
  return managementFetchJson(
    `/api/meetings/${encodeURIComponent(meetingId)}/chat/messages?afterId=${afterId}&size=100`,
    { headers: { [MEETING_PARTICIPANT_TOKEN_HEADER]: participantToken }, signal },
  );
}
