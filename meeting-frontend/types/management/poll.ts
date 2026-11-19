import type { ManagementMeetingRole, ManagementMeetingStatus } from './meeting';

export type PollStatus = 'DRAFT' | 'OPEN' | 'CLOSED' | 'CANCELLED';
export type PollType = 'YES_NO_ABSTAIN' | 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE';
export type PollResultVisibility = 'LIVE' | 'AFTER_CLOSE' | 'AUTHORIZED_ONLY';
export type PollOptionCode = 'YES' | 'NO' | 'ABSTAIN';

export interface MeetingPoll {
  id: number;
  meetingId: number;
  question: string;
  description: string | null;
  type: PollType;
  status: PollStatus;
  allowChangeVote: boolean;
  resultVisibility: PollResultVisibility;
  minSelections: number;
  maxSelections: number;
  createdAt: string;
  openedAt: string | null;
  closedAt: string | null;
  cancelledAt: string | null;
  canManage: boolean;
  options: { id: number; label: string; code: PollOptionCode | null; sortOrder: number }[];
  eligibleParticipantKeys: string[];
  eligibleCount: number;
  votedCount: number;
  canVote: boolean;
  canSeeResults: boolean;
  myVote: { optionIds: number[]; submittedAt: string; updatedAt: string } | null;
  results: { optionId: number; count: number; percentage: number }[] | null;
}

export interface PollContext {
  canManage: boolean;
  meetingStatus: ManagementMeetingStatus;
  candidates: { participantKey: string; fullName: string; role: ManagementMeetingRole }[];
}

export interface PollDraftInput {
  question: string;
  description?: string;
  type: PollType;
  allowChangeVote: boolean;
  resultVisibility: PollResultVisibility;
  minSelections: number;
  maxSelections: number;
  options?: { label: string; code?: PollOptionCode }[];
  eligibleParticipantKeys: string[];
}

export interface PollPage {
  content: MeetingPoll[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export const POLL_STATUS_LABELS: Record<PollStatus, string> = {
  DRAFT: 'Bản nháp',
  OPEN: 'Đang biểu quyết',
  CLOSED: 'Đã đóng',
  CANCELLED: 'Đã hủy',
};
