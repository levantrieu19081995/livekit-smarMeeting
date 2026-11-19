export interface MeetingPoint {
  id: number;
  name: string;
}

export type SpeakingStatus = 'WAITING' | 'SPEAKING' | 'FINISHED' | 'CANCELLED';

export interface SpeakingContext {
  pointMeeting: boolean;
  canModerate: boolean;
  readOnly: boolean;
  canRequest: boolean;
  pointId: number | null;
  pointName: string | null;
  displayName: string | null;
  points: MeetingPoint[];
}

export interface SpeakingRequest {
  id: number;
  requestId: string;
  version: number;
  pointId: number;
  pointName: string;
  speakerName: string;
  requestedByDisplayName: string;
  approvedByDisplayName: string | null;
  finishedByDisplayName: string | null;
  status: SpeakingStatus;
  requestedAt: string;
  approvedAt: string | null;
  finishedAt: string | null;
  isOwn: boolean;
  canApprove: boolean;
  canFinish: boolean;
  canCancel: boolean;
}

export interface SpeakingPage {
  content: SpeakingRequest[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface CreateSpeakingRequest {
  requestId: string;
  speakerName?: string;
}

export type SpeakingAction = 'approve' | 'finish' | 'cancel';

export const SPEAKING_STATUS_LABELS: Record<SpeakingStatus, string> = {
  WAITING: 'Chờ duyệt',
  SPEAKING: 'Đang phát biểu',
  FINISHED: 'Đã kết thúc',
  CANCELLED: 'Đã hủy',
};
