import { ManagementApiError, managementFetchBlob, managementFetchJson } from './httpClient';
import type {
  AskQnaQuestionInput,
  QnaContext,
  QnaFilter,
  QnaPage,
  QnaQuestion,
  QnaModerationAction,
  QnaSettingsInput,
  QnaSort,
} from '@/types/management/qna';

const PARTICIPANT_TOKEN_HEADER = 'X-Meeting-Participant-Token';

function path(meetingId: string, suffix: string) {
  return `/api/meetings/${encodeURIComponent(meetingId)}/qa${suffix}`;
}

function options(participantToken?: string, signal?: AbortSignal): RequestInit {
  return {
    signal,
    ...(participantToken ? { headers: { [PARTICIPANT_TOKEN_HEADER]: participantToken } } : {}),
  };
}

export function getQnaContext(meetingId: string, participantToken?: string, signal?: AbortSignal) {
  return managementFetchJson<QnaContext>(
    path(meetingId, '/context'),
    options(participantToken, signal),
  );
}

export function getQnaQuestions(
  meetingId: string,
  query: { page: number; size?: number; sort: QnaSort; filter: QnaFilter },
  participantToken?: string,
  signal?: AbortSignal,
) {
  const search = new URLSearchParams({
    page: String(query.page),
    size: String(query.size ?? 20),
    sort: query.sort,
    filter: query.filter,
  });
  return managementFetchJson<QnaPage>(
    path(meetingId, `/questions?${search}`),
    options(participantToken, signal),
  );
}

export function askQnaQuestion(
  meetingId: string,
  input: AskQnaQuestionInput,
  participantToken?: string,
  signal?: AbortSignal,
) {
  return managementFetchJson<QnaQuestion>(path(meetingId, '/questions'), {
    ...options(participantToken, signal),
    method: 'POST',
    body: JSON.stringify({
      clientQuestionId: input.clientQuestionId,
      content: input.content.trim(),
      anonymous: input.anonymous,
    }),
  });
}

export function setQnaUpvote(
  meetingId: string,
  questionId: number,
  upvoted: boolean,
  participantToken?: string,
  signal?: AbortSignal,
) {
  return managementFetchJson<QnaQuestion>(
    path(meetingId, `/questions/${encodeURIComponent(String(questionId))}/upvote`),
    {
      ...options(participantToken, signal),
      method: 'PUT',
      body: JSON.stringify({ upvoted }),
    },
  );
}

export function updateQnaSettings(
  meetingId: string,
  input: QnaSettingsInput,
  participantToken?: string,
  signal?: AbortSignal,
) {
  return managementFetchJson<QnaContext>(path(meetingId, '/settings'), {
    ...options(participantToken, signal),
    method: 'PUT',
    body: JSON.stringify(input),
  });
}

export function moderateQnaQuestion(
  meetingId: string,
  questionId: number,
  action: QnaModerationAction,
  expectedVersion: number,
  participantToken?: string,
  signal?: AbortSignal,
  answer?: string,
) {
  return managementFetchJson<QnaQuestion>(
    path(meetingId, `/questions/${encodeURIComponent(String(questionId))}/actions`),
    {
      ...options(participantToken, signal),
      method: 'POST',
      body: JSON.stringify({
        action,
        expectedVersion,
        ...(answer !== undefined ? { answer: answer.trim() } : {}),
      }),
    },
  );
}

export async function getQnaReport(
  meetingId: string,
  participantToken?: string,
  signal?: AbortSignal,
) {
  const { blob, response } = await managementFetchBlob(
    path(meetingId, '/report'),
    options(participantToken, signal),
  );
  const contentType = response.headers.get('content-type') ?? blob.type;
  if (!contentType?.toLowerCase().startsWith('text/csv') || blob.size === 0) {
    throw new ManagementApiError('Máy chủ chưa trả về báo cáo hỏi đáp hợp lệ.', 502);
  }
  const safeId = meetingId.replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 64) || 'meeting';
  return { blob, filename: `meeting-${safeId}-qa.csv` };
}
