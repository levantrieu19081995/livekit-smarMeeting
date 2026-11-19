export type MeetingDocumentSource = 'BEFORE_MEETING' | 'IN_MEETING' | 'AFTER_MEETING';

export interface MeetingDocument {
  id: string;
  meetingId: string;
  fileName: string;
  originalFileName: string;
  mimeType: string;
  fileSize: number;
  uploadedBy: {
    id: string;
    displayName: string;
  };
  uploadedAt: string;
  source: MeetingDocumentSource;
}

export interface MeetingDocumentApiResponse {
  id: number;
  originalFileName: string | null;
  storedFileName: string | null;
  contentType: string | null;
  fileSize: number | null;
  storagePath?: string | null;
  createdAt: string | null;
  meetingId: number;
  uploadedBy: {
    id: string | null;
    displayName: string;
  } | null;
  source: MeetingDocumentSource;
}

export interface LocalMeetingDocument {
  id: string;
  name: string;
  size?: number;
  contentType?: string;
  file: File;
}
