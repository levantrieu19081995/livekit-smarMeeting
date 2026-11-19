import { managementFetchBlob, managementFetchJson } from '@/services/management/httpClient';
import type { MeetingDocument, MeetingDocumentApiResponse } from '@/types/management/documents';

function mapDocument(document: MeetingDocumentApiResponse): MeetingDocument {
  const fileName =
    document.originalFileName || document.storedFileName || `document-${document.id}`;
  return {
    id: String(document.id),
    meetingId: String(document.meetingId),
    fileName,
    originalFileName: fileName,
    mimeType: document.contentType || 'application/octet-stream',
    fileSize: document.fileSize ?? 0,
    uploadedBy: {
      id: document.uploadedBy?.id ?? '',
      displayName: document.uploadedBy?.displayName ?? 'Chưa xác định',
    },
    uploadedAt: document.createdAt ?? new Date(0).toISOString(),
    source: document.source,
  };
}

export async function getMeetingDocuments(meetingId: string): Promise<MeetingDocument[]> {
  const response = await managementFetchJson<MeetingDocumentApiResponse[]>(
    `/api/meetings/${encodeURIComponent(meetingId)}/documents`,
  );
  return response.map(mapDocument);
}

export async function uploadMeetingDocument(
  meetingId: string,
  file: File,
): Promise<MeetingDocument> {
  const formData = new FormData();
  formData.append('file', file);
  const response = await managementFetchJson<MeetingDocumentApiResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/documents`,
    {
      method: 'POST',
      body: formData,
    },
  );
  return mapDocument(response);
}

function filenameFromContentDisposition(header: string | null): string | null {
  if (!header) return null;
  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1]);
    } catch {
      return null;
    }
  }
  const regularMatch = /filename="?([^";]+)"?/i.exec(header);
  return regularMatch?.[1] ?? null;
}

async function getDocumentBlob(
  meetingId: string,
  documentId: string,
  action: 'view' | 'download',
): Promise<{ blob: Blob; fileName: string | null }> {
  const { response, blob } = await managementFetchBlob(
    `/api/meetings/${encodeURIComponent(meetingId)}/documents/${encodeURIComponent(documentId)}/${action}`,
  );
  return {
    blob,
    fileName: filenameFromContentDisposition(response.headers.get('content-disposition')),
  };
}

export function viewMeetingDocument(meetingId: string, documentId: string) {
  return getDocumentBlob(meetingId, documentId, 'view');
}

export function downloadMeetingDocument(meetingId: string, documentId: string) {
  return getDocumentBlob(meetingId, documentId, 'download');
}
