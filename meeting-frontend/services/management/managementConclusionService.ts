import { managementFetchJson } from './httpClient';
import type { ConclusionSource, MeetingConclusion } from '@/types/management/conclusion';

export function getMeetingConclusion(meetingId: string, signal?: AbortSignal) {
  return managementFetchJson<MeetingConclusion>(`/api/meetings/${meetingId}/conclusion`, {
    signal,
  });
}

export function generateMeetingConclusion(meetingId: string, source: ConclusionSource) {
  return managementFetchJson<MeetingConclusion>(`/api/meetings/${meetingId}/conclusion/generate`, {
    method: 'POST',
    body: JSON.stringify(source),
  });
}
