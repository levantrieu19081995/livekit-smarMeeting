import { managementFetchJson } from './httpClient';
import type {
  CreateSpeakingRequest,
  SpeakingAction,
  SpeakingContext,
  SpeakingPage,
  SpeakingRequest,
} from '@/types/management/speaking';

function path(meetingId: string, suffix: string) {
  return `/api/meetings/${encodeURIComponent(meetingId)}/speaking${suffix}`;
}

function options(participantToken?: string, signal?: AbortSignal): RequestInit {
  return {
    signal,
    ...(participantToken ? { headers: { 'X-Meeting-Participant-Token': participantToken } } : {}),
  };
}

export function getSpeakingContext(
  meetingId: string,
  participantToken?: string,
  signal?: AbortSignal,
) {
  return managementFetchJson<SpeakingContext>(
    path(meetingId, '/context'),
    options(participantToken, signal),
  );
}

export function getSpeakingRequests(
  meetingId: string,
  page: number,
  participantToken?: string,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({ page: String(page), size: '20' });
  return managementFetchJson<SpeakingPage>(
    path(meetingId, `/requests?${query}`),
    options(participantToken, signal),
  );
}

export function createSpeakingRequest(
  meetingId: string,
  input: CreateSpeakingRequest,
  participantToken?: string,
  signal?: AbortSignal,
) {
  return managementFetchJson<SpeakingRequest>(path(meetingId, '/requests'), {
    ...options(participantToken, signal),
    method: 'POST',
    body: JSON.stringify({
      requestId: input.requestId,
      speakerName: input.speakerName?.trim() || '',
    }),
  });
}

export function actOnSpeakingRequest(
  meetingId: string,
  requestId: number,
  action: SpeakingAction,
  version: number,
  participantToken?: string,
  signal?: AbortSignal,
) {
  return managementFetchJson<SpeakingRequest>(
    path(meetingId, `/requests/${encodeURIComponent(String(requestId))}/${action}`),
    {
      ...options(participantToken, signal),
      method: 'PATCH',
      body: JSON.stringify({ version }),
    },
  );
}
