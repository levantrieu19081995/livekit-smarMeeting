import type { MeetingDocumentSource } from '@/types/management/documents';

export type ManagementMeetingStatus = 'UPCOMING' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

export type ManagementMeetingAccessType = 'PRIVATE' | 'PUBLIC';
export type ManagementMeetingCreationMode = 'INSTANT' | 'LATER' | 'SCHEDULED' | 'POINT';

export interface ManagementMeetingPoint {
  id: number;
  name: string;
}

export type ManagementMeetingRole = 'CHAIRMAN' | 'SECRETARY' | 'MEMBER' | 'GUEST';

export type ManagementParticipantStatus = 'INVITED' | 'JOINED' | 'LEFT' | 'REMOVED';

export interface ManagementMeetingParticipant {
  id?: string;
  participantKey?: string;
  userId: string;
  name: string;
  email: string;
  role: ManagementMeetingRole;
  status?: ManagementParticipantStatus;
  joinedAt?: string | null;
  leftAt?: string | null;
}

export interface ManagementMeetingDocument {
  id: string;
  name: string;
  size?: number;
  contentType?: string;
  createdAt?: string;
  file?: File;
  meetingId?: string;
  fileName?: string;
  originalFileName?: string;
  mimeType?: string;
  fileSize?: number;
  uploadedBy?: {
    id: string;
    displayName: string;
  };
  uploadedAt?: string;
  source?: MeetingDocumentSource;
}

export interface ManagementMeeting {
  id: string;
  meetingCode?: string;
  title: string;
  description?: string;
  roomName: string;
  roomSid?: string;
  meetingUrl?: string;
  startTime: string | null;
  endTime: string | null;
  status: ManagementMeetingStatus;
  accessType: ManagementMeetingAccessType;
  creationMode?: ManagementMeetingCreationMode;
  points?: ManagementMeetingPoint[];
  pointsEditable?: boolean;
  canManage?: boolean;
  participants: ManagementMeetingParticipant[];
  documents: ManagementMeetingDocument[];
}

export interface ManagementMeetingMutationInput {
  title: string;
  description?: string;
  startTime?: string | null;
  endTime?: string | null;
  accessType: ManagementMeetingAccessType;
  creationMode?: ManagementMeetingCreationMode;
  pointNames?: string[];
  clientRequestId?: string;
  participants: ManagementMeetingParticipant[];
  documents: ManagementMeetingDocument[];
  status?: ManagementMeetingStatus;
}

export interface ManagementMeetingListParams {
  search?: string;
  status?: ManagementMeetingStatus | 'ALL';
  accessType?: ManagementMeetingAccessType | 'ALL';
  page?: number;
  size?: number;
  sort?: string;
}

export const MANAGEMENT_MEETING_STATUS_LABELS: Record<ManagementMeetingStatus, string> = {
  UPCOMING: 'Sắp diễn ra',
  IN_PROGRESS: 'Đang diễn ra',
  COMPLETED: 'Đã kết thúc',
  CANCELLED: 'Đã hủy',
};

export const MANAGEMENT_MEETING_ACCESS_TYPE_LABELS: Record<ManagementMeetingAccessType, string> = {
  PRIVATE: 'Riêng tư',
  PUBLIC: 'Tự do',
};

export const MANAGEMENT_MEETING_ROLE_LABELS: Record<ManagementMeetingRole, string> = {
  CHAIRMAN: 'Chủ tọa',
  SECRETARY: 'Thư ký',
  MEMBER: 'Thành viên',
  GUEST: 'Khách mời',
};

export const MANAGEMENT_PARTICIPANT_STATUS_LABELS: Record<ManagementParticipantStatus, string> = {
  INVITED: 'Đã mời',
  JOINED: 'Đã tham gia',
  LEFT: 'Đã rời',
  REMOVED: 'Đã gỡ',
};
import type { LocalMeetingDocument, MeetingDocument } from '@/types/management/documents';
