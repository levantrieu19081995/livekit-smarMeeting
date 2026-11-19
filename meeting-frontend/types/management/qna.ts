import type { ManagementMeetingStatus } from './meeting';

export type QnaQuestionStatus = 'PENDING' | 'OPEN' | 'ANSWERED' | 'HIDDEN' | 'DELETED';
export type QnaSort = 'NEWEST' | 'OLDEST' | 'POPULAR';
export type QnaFilter = 'ALL' | 'MINE' | 'PENDING';

export interface QnaContext {
  enabled: boolean;
  anonymousAllowed: boolean;
  moderationEnabled: boolean;
  settingsVersion: number;
  meetingStatus: ManagementMeetingStatus;
  canManage: boolean;
  canAsk: boolean;
  canUpvote: boolean;
}

export interface QnaQuestion {
  id: number;
  clientQuestionId: string | null;
  content: string;
  anonymous: boolean;
  authorDisplayName: string;
  status: QnaQuestionStatus;
  createdAt: string;
  updatedAt: string;
  answeredAt: string | null;
  answer: string | null;
  answerAuthorDisplayName: string | null;
  upvoteCount: number;
  upvotedByMe: boolean;
  mine: boolean;
  version: number;
}

export interface QnaPage {
  content: QnaQuestion[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface AskQnaQuestionInput {
  clientQuestionId: string;
  content: string;
  anonymous: boolean;
}

export type QnaModerationAction = 'APPROVE' | 'ANSWER' | 'HIDE' | 'DELETE';

export interface QnaSettingsInput {
  enabled: boolean;
  anonymousAllowed: boolean;
  moderationEnabled: boolean;
  expectedVersion: number;
}

export const QNA_STATUS_LABELS: Record<QnaQuestionStatus, string> = {
  PENDING: 'Chờ duyệt',
  OPEN: 'Chờ trả lời',
  ANSWERED: 'Đã trả lời',
  HIDDEN: 'Đã ẩn',
  DELETED: 'Đã xóa',
};
