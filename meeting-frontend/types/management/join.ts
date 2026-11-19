import type { ManagementMeetingCreationMode, ManagementMeetingPoint } from './meeting';

export type ManagementParticipantJoinType = 'AUTHENTICATED' | 'GUEST';

export interface ManagementMeetingJoinInfo {
  id: number;
  title: string | null;
  startTime: string | null;
  endTime: string | null;
  status: 'UPCOMING' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
  accessType: 'PRIVATE' | 'PUBLIC';
  creationMode?: ManagementMeetingCreationMode;
  points?: ManagementMeetingPoint[];
}

export interface ManagementCurrentUser {
  attendeeId: string;
  publicId?: string;
  name: string;
  email?: string;
  kind: ManagementParticipantJoinType;
  systemRole?: 'ADMIN' | 'MEETING_MANAGER' | 'USER';
}

export interface ManagementJoinRequest {
  participantType: ManagementParticipantJoinType;
  guestId?: string;
  guestResumeToken?: string;
  displayName: string;
  pointId?: number;
}

export interface ManagementJoinResponse {
  roomName: string;
  roomSid?: string;
  serverUrl: string;
  token: string;
  participant: {
    attendeeId: string;
    connectionId: string;
    liveKitIdentity: string;
    displayName: string;
    role: 'CHAIRMAN' | 'SECRETARY' | 'MEMBER' | 'GUEST';
    chatWriteToken: string;
    pointId?: number | null;
    pointName?: string | null;
  };
  permissions: {
    canEndMeeting: boolean;
    canManageParticipants: boolean;
  };
}
