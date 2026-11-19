import { managementFetchJson } from '@/services/management/httpClient';
import type { ManagementCaptureResponse } from '@/types/management/capture';

export function getManagementCaptureStatus(meetingId: string, signal?: AbortSignal) {
  return managementFetchJson<ManagementCaptureResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/capture/status`,
    ...(signal ? [{ signal }] : []),
  );
}

export function getManagementCaptureSessions(meetingId: string) {
  return managementFetchJson<ManagementCaptureResponse[]>(
    `/api/meetings/${encodeURIComponent(meetingId)}/capture/sessions`,
  );
}

export function startManagementCapture(meetingId: string, signal?: AbortSignal) {
  return managementFetchJson<ManagementCaptureResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/capture/start`,
    { method: 'POST', ...(signal ? { signal } : {}) },
  );
}

export function stopManagementCapture(meetingId: string, signal?: AbortSignal) {
  return managementFetchJson<ManagementCaptureResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/capture/stop`,
    { method: 'POST', ...(signal ? { signal } : {}) },
  );
}
