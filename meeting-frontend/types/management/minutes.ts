import type {
  MeetingDocument,
  MeetingDocumentApiResponse,
  MeetingDocumentSource,
} from '@/types/management/documents';

export type MinutesStatus = 'NOT_GENERATED' | 'PROCESSING' | 'DRAFT' | 'FINAL' | 'FAILED';

export type MinutesVersionSource =
  | 'SYSTEM_GENERATED'
  | 'AI_GENERATED'
  | 'SECRETARY_EDITED'
  | 'CHAIRMAN_EDITED'
  | 'OWNER_EDITED';
export type MinutesJobStatus = 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
export type MinutesProcessingStage =
  | 'VALIDATING'
  | 'PREPARING_AUDIO'
  | 'TRANSCRIBING'
  | 'BUILDING_TRANSCRIPT'
  | 'GENERATING_MINUTES'
  | 'DRAFT_READY'
  | 'COMPLETED'
  | 'FAILED';

export type MinutesParticipantRole = 'CHAIRMAN' | 'SECRETARY' | 'MEMBER';
export type MinutesDetailTab = 'MINUTES' | 'TRANSCRIPT' | 'DETAILS';
export type MinutesDetailMode = 'VIEW' | 'EDIT';
export type MinutesTableAction = 'PRIMARY' | 'DOCUMENTS' | 'DETAILS' | 'OPEN_MEETING';
export type { MeetingDocument, MeetingDocumentApiResponse, MeetingDocumentSource };

export interface MinutesParticipant {
  id: string;
  name: string;
  initials: string;
  role: MinutesParticipantRole;
  status?: 'INVITED' | 'JOINED' | 'LEFT' | 'REMOVED';
}

export interface MinutesTranscriptEntry {
  id: string;
  timestamp: string;
  participantId: string;
  speakerName: string;
  text: string;
}

export interface MinutesTranscriptEntryApiResponse {
  id: number;
  attendeeId: string | null;
  speakerName: string | null;
  absoluteStartTime: string | null;
  segmentStartedAt: string | null;
  chunkStartMs: number;
  text: string;
}

export interface MinutesAssignedTask {
  id: string;
  description: string;
  assignee: string;
  deadline: string;
}

export interface MeetingMinutesVersion {
  revision?: number;
  id?: string;
  meetingId?: string;
  version: number;
  status?: MinutesStatus;
  source: MinutesVersionSource;
  createdBy: string;
  createdAt: string;
  updatedAt?: string;
  finalizedAt?: string;
  content: string;
  contentJson?: MinutesContentJson;
}

export interface MeetingMinutesContent {
  location: string;
  discussionItems: string[];
  conclusions: string[];
  tasks: MinutesAssignedTask[];
}

export interface MeetingMinutesListItem {
  meetingId: string;
  meetingCode: string;
  title: string;
  date: string;
  startTime: string;
  endTime: string;
  chairmanName: string;
  secretaryName: string;
  participants: MinutesParticipant[];
  participantCount: number;
  status: MinutesStatus;
  progress?: number;
  currentVersion?: number;
  updatedAt?: string;
  myRole: MinutesParticipantRole | null;
  canManage?: boolean;
  durationLabel: string;
  recordingSessionCount: number;
  recordingStatus: 'NOT_RECORDED' | 'COMPLETED';
  accessType: 'PUBLIC' | 'PRIVATE';
  meetingStatus: 'ENDED';
  content: MeetingMinutesContent;
  versions: MeetingMinutesVersion[];
  transcript: MinutesTranscriptEntry[];
  documents: MeetingDocument[];
}

export interface MinutesListFilters {
  search: string;
  status: 'ALL' | MinutesStatus;
  time: 'ALL' | 'TODAY' | 'LAST_7_DAYS' | 'LAST_30_DAYS';
  role: 'ALL' | MinutesParticipantRole;
}

export interface MinutesSummary {
  totalMeetings: number;
  notGenerated: number;
  processing: number;
  available: number;
}

export interface MinutesPage<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface AccountSummaryApiResponse {
  id: string | null;
  displayName: string;
}

export interface MinutesParticipantPreviewApiResponse {
  id: string;
  displayName: string;
}

export interface MinutesListItemApiResponse {
  meetingId: number;
  meetingCode: string | null;
  title: string;
  date: string | null;
  startTime: string | null;
  endTime: string | null;
  chairman: AccountSummaryApiResponse | null;
  participantCount: number;
  participantPreview: MinutesParticipantPreviewApiResponse[];
  minutesStatus: MinutesStatus;
  currentVersion: number | null;
  updatedAt: string | null;
  myRole: MinutesParticipantRole | null;
  canManage?: boolean;
}

export interface MinutesSummaryApiResponse {
  totalMeetings: number;
  notGenerated: number;
  processing: number;
  available: number;
}

export type MinutesContentJson =
  | string
  | {
      mainContent?: string;
      conclusion?: string;
      text?: string;
      content?: string;
      minutesText?: string;
      location?: string;
      discussionItems?: string[];
      conclusions?: string[];
      tasks?: MinutesAssignedTask[];
      [key: string]: unknown;
    };

export interface MinutesEditorContent {
  mainContent: string;
  conclusion: string;
}

export interface MinutesTemplateApiResponse {
  code: string;
  name: string;
  description: string;
}

export interface MinutesRenderApiResponse {
  meetingId: number;
  version: number;
  status: MinutesStatus;
  template: MinutesTemplateApiResponse;
  html: string;
  canExportPdf: boolean;
}

export interface MinutesVersionApiResponse {
  revision?: number;
  id: number;
  meetingId: number;
  version: number;
  status: MinutesStatus;
  source: MinutesVersionSource;
  content: MinutesContentJson;
  createdBy: AccountSummaryApiResponse | null;
  createdAt: string;
  updatedAt: string;
  finalizedAt: string | null;
}

export interface MinutesProcessingApiResponse {
  jobId: number;
  meetingId: number;
  status: MinutesJobStatus;
  stage: MinutesProcessingStage;
  progressPercent: number;
  attempt: number;
  message: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface MinutesListParams {
  filters: MinutesListFilters;
  page: number;
  size: number;
}
