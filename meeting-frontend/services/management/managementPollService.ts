import { managementFetchJson } from './httpClient';
import type { MeetingPoll, PollContext, PollDraftInput, PollPage } from '@/types/management/poll';

function participantHeaders(token?: string): HeadersInit | undefined {
  return token ? { 'X-Meeting-Participant-Token': token } : undefined;
}

function path(meetingId: string, pollId?: number) {
  const base = '/api/meetings/' + encodeURIComponent(meetingId) + '/polls';
  return pollId == null ? base : base + '/' + encodeURIComponent(String(pollId));
}

export function getPollContext(meetingId: string, signal?: AbortSignal, participantToken?: string) {
  return managementFetchJson<PollContext>(path(meetingId) + '/context', {
    signal,
    headers: participantHeaders(participantToken),
  });
}

export function getPolls(
  meetingId: string,
  page = 0,
  signal?: AbortSignal,
  participantToken?: string,
) {
  return managementFetchJson<PollPage>(path(meetingId) + '?page=' + page + '&size=20', {
    signal,
    headers: participantHeaders(participantToken),
  });
}

export async function getActivePoll(
  meetingId: string,
  signal?: AbortSignal,
  participantToken?: string,
) {
  const result = await managementFetchJson<MeetingPoll | undefined>(path(meetingId) + '/active', {
    signal,
    headers: participantHeaders(participantToken),
  });
  return result ?? null;
}

export function getPoll(
  meetingId: string,
  pollId: number,
  signal?: AbortSignal,
  participantToken?: string,
) {
  return managementFetchJson<MeetingPoll>(path(meetingId, pollId), {
    signal,
    headers: participantHeaders(participantToken),
  });
}

export function createPoll(
  meetingId: string,
  input: PollDraftInput,
  signal?: AbortSignal,
  participantToken?: string,
) {
  return managementFetchJson<MeetingPoll>(path(meetingId), {
    method: 'POST',
    signal,
    headers: participantHeaders(participantToken),
    body: JSON.stringify(input),
  });
}

export function updatePoll(
  meetingId: string,
  pollId: number,
  input: PollDraftInput,
  signal?: AbortSignal,
  participantToken?: string,
) {
  return managementFetchJson<MeetingPoll>(path(meetingId, pollId), {
    method: 'PUT',
    signal,
    headers: participantHeaders(participantToken),
    body: JSON.stringify(input),
  });
}

export function transitionPoll(
  meetingId: string,
  pollId: number,
  action: 'open' | 'close' | 'cancel',
  signal?: AbortSignal,
  participantToken?: string,
) {
  return managementFetchJson<MeetingPoll>(path(meetingId, pollId) + '/' + action, {
    method: 'POST',
    signal,
    headers: participantHeaders(participantToken),
  });
}

export function submitPollVote(
  meetingId: string,
  pollId: number,
  optionIds: number[],
  signal?: AbortSignal,
  participantToken?: string,
) {
  return managementFetchJson<MeetingPoll>(path(meetingId, pollId) + '/my-vote', {
    method: 'PUT',
    signal,
    headers: participantHeaders(participantToken),
    body: JSON.stringify({ optionIds }),
  });
}
