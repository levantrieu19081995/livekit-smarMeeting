import { getMeetingDocuments } from '@/services/management/managementDocumentService';
import {
  ManagementApiError,
  managementFetchBlob,
  managementFetchJson,
} from '@/services/management/httpClient';
import type {
  ManagementMeetingAccessType,
  ManagementMeetingStatus,
} from '@/types/management/meeting';
import type { MeetingDocument } from '@/types/management/documents';
import type {
  AccountSummaryApiResponse,
  MeetingMinutesContent,
  MeetingMinutesListItem,
  MeetingMinutesVersion,
  MinutesContentJson,
  MinutesEditorContent,
  MinutesListItemApiResponse,
  MinutesListParams,
  MinutesPage,
  MinutesParticipant,
  MinutesParticipantRole,
  MinutesProcessingApiResponse,
  MinutesRenderApiResponse,
  MinutesSummary,
  MinutesSummaryApiResponse,
  MinutesTemplateApiResponse,
  MinutesTranscriptEntry,
  MinutesTranscriptEntryApiResponse,
  MinutesVersionApiResponse,
} from '@/types/management/minutes';

export type { MinutesProcessingApiResponse } from '@/types/management/minutes';

interface BackendMeetingDetailResponse {
  id: number;
  meetingCode?: string | null;
  title: string;
  description?: string | null;
  roomName: string;
  roomSid?: string | null;
  meetingUrl?: string | null;
  startTime: string | null;
  endTime: string | null;
  status: ManagementMeetingStatus;
  accessType: ManagementMeetingAccessType;
  participants: BackendMeetingParticipantResponse[];
  canManage?: boolean;
}

interface BackendMeetingParticipantResponse {
  id?: number | null;
  participantKey?: string | null;
  userId?: string | null;
  userPublicId?: string | null;
  invitedEmail?: string | null;
  fullName: string;
  email?: string | null;
  role: MinutesParticipantRole | 'GUEST';
  status?: MinutesParticipant['status'];
}

export interface MinutesDetailData {
  meeting: MeetingMinutesListItem;
  current: MeetingMinutesVersion | null;
  versions: MeetingMinutesVersion[];
}

export type MinutesPdfDownload = {
  blob: Blob;
  filename: string;
};

function initials(name: string) {
  const parts = name.trim().split(/\s+/).slice(-2);
  return (parts.length > 0 ? parts : [name])
    .map((part) => part[0] ?? '')
    .join('')
    .toUpperCase();
}

function normalizeRole(
  role: MinutesParticipantRole | 'GUEST' | null | undefined,
): MinutesParticipantRole {
  if (role === 'CHAIRMAN' || role === 'SECRETARY') return role;
  return 'MEMBER';
}

function accountName(
  account: AccountSummaryApiResponse | null | undefined,
  fallback = 'Chưa xác định',
) {
  return account?.displayName || fallback;
}

function timePart(value: string | null) {
  if (!value) return '';
  return value.includes('T') ? (value.split('T')[1]?.slice(0, 5) ?? value) : value.slice(0, 5);
}

function datePart(value: string | null) {
  if (!value) return '';
  return value.includes('T') ? (value.split('T')[0] ?? value) : value;
}

function filenameFromContentDisposition(value: string | null): string | null {
  const match = value?.match(/filename="?([^";]+)"?/i);
  const filename = match?.[1]?.trim();
  if (!filename) return null;
  const parts = filename.split(/[\\/]+/);
  return parts[parts.length - 1] || null;
}

function durationLabel(startTime: string | null, endTime: string | null) {
  if (!startTime || !endTime) return 'Không đặt giờ kết thúc';
  const start = new Date(startTime).getTime();
  const end = new Date(endTime).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return '-';
  const minutes = Math.round((end - start) / 60_000);
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours > 0 && rest > 0) return `${hours} giờ ${rest} phút`;
  if (hours > 0) return `${hours} giờ`;
  return `${rest} phút`;
}

function formatTranscriptTimestamp(
  absoluteStartTime: string | null,
  segmentStartedAt: string | null,
  chunkStartMs: number,
) {
  const absolute = parseTimestamp(absoluteStartTime);
  if (absolute != null) return formatTimeOfDay(absolute);

  const segmentStart = parseTimestamp(segmentStartedAt);
  if (segmentStart == null) return '-';
  return formatTimeOfDay(new Date(segmentStart.getTime() + chunkStartMs));
}

function parseTimestamp(value: string | null) {
  if (value == null || value.trim() === '') return null;
  const parsed = new Date(value);
  return Number.isFinite(parsed.getTime()) ? parsed : null;
}

function formatTimeOfDay(value: Date) {
  const hours = String(value.getHours()).padStart(2, '0');
  const minutes = String(value.getMinutes()).padStart(2, '0');
  const seconds = String(value.getSeconds()).padStart(2, '0');
  return `${hours}:${minutes}:${seconds}`;
}

function emptyMinutesContent(): MeetingMinutesContent {
  return {
    location: 'Phòng họp trực tuyến (Smart Meeting)',
    discussionItems: [],
    conclusions: [],
    tasks: [],
  };
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : [];
}

function taskArray(value: unknown) {
  if (!Array.isArray(value)) return [];
  return value
    .filter((item): item is Record<string, unknown> => item != null && typeof item === 'object')
    .map((item, index) => ({
      id: typeof item.id === 'string' ? item.id : `task-${index + 1}`,
      description: typeof item.description === 'string' ? item.description : '',
      assignee: typeof item.assignee === 'string' ? item.assignee : '',
      deadline: typeof item.deadline === 'string' ? item.deadline : '',
    }))
    .filter((item) => item.description.length > 0);
}

export function contentJsonToText(content: MinutesContentJson | null | undefined): string {
  if (content == null) return '';
  if (typeof content === 'string') return content;
  if (typeof content.text === 'string') return content.text;
  if (typeof content.content === 'string') return content.content;
  if (typeof content.minutesText === 'string') return content.minutesText;
  return JSON.stringify(content, null, 2);
}

export function contentJsonToMinutesContent(
  content: MinutesContentJson | null | undefined,
): MeetingMinutesContent {
  if (content == null || typeof content === 'string') return emptyMinutesContent();
  return {
    location:
      typeof content.location === 'string' ? content.location : emptyMinutesContent().location,
    discussionItems: stringArray(content.discussionItems),
    conclusions: stringArray(content.conclusions),
    tasks: taskArray(content.tasks),
  };
}

export function contentJsonToEditorContent(
  content: MinutesContentJson | null | undefined,
): MinutesEditorContent {
  if (content == null) {
    return {
      mainContent: '',
      conclusion: '',
    };
  }

  if (typeof content === 'string') {
    return {
      mainContent: content,
      conclusion: '',
    };
  }

  let mainContent = '';
  if (typeof content.mainContent === 'string') {
    mainContent = content.mainContent;
  } else if (typeof content.text === 'string') {
    mainContent = content.text;
  } else if (typeof content.content === 'string') {
    mainContent = content.content;
  } else if (typeof content.minutesText === 'string') {
    mainContent = content.minutesText;
  } else if (Array.isArray(content.discussionItems)) {
    mainContent = content.discussionItems
      .filter((item): item is string => typeof item === 'string')
      .join('\n');
  }

  let conclusion = '';
  if (typeof content.conclusion === 'string') {
    conclusion = content.conclusion;
  } else if (Array.isArray(content.conclusions)) {
    conclusion = content.conclusions
      .filter((item): item is string => typeof item === 'string')
      .join('\n');
  }

  return {
    mainContent,
    conclusion,
  };
}

export function editorContentToContentJson(
  mainContent: string,
  conclusion: string,
  previous?: MinutesContentJson,
): Exclude<MinutesContentJson, string> {
  const previousObject = previous != null && typeof previous === 'object' ? previous : {};
  return {
    ...previousObject,
    mainContent,
    conclusion,
  };
}

export function minutesTextToContentJson(
  text: string,
  previous?: MinutesContentJson,
): Exclude<MinutesContentJson, string> {
  const previousObject = previous != null && typeof previous === 'object' ? previous : {};
  return {
    ...previousObject,
    text,
  };
}

function mapParticipantPreview(
  participant: MinutesListItemApiResponse['participantPreview'][number],
): MinutesParticipant {
  return {
    id: participant.id,
    name: participant.displayName,
    initials: initials(participant.displayName),
    role: 'MEMBER',
  };
}

function mapBackendParticipant(participant: BackendMeetingParticipantResponse): MinutesParticipant {
  const id =
    participant.userPublicId ||
    participant.userId ||
    participant.participantKey ||
    (participant.id == null ? participant.fullName : String(participant.id));
  return {
    id,
    name: participant.fullName,
    initials: initials(participant.fullName),
    role: normalizeRole(participant.role),
    ...(participant.status ? { status: participant.status } : {}),
  };
}

function mapTranscriptEntry(entry: MinutesTranscriptEntryApiResponse): MinutesTranscriptEntry {
  const attendeeId = entry.attendeeId?.trim();
  return {
    id: String(entry.id),
    timestamp: formatTranscriptTimestamp(
      entry.absoluteStartTime,
      entry.segmentStartedAt,
      entry.chunkStartMs,
    ),
    participantId: normalizeTranscriptParticipantId(attendeeId, entry.id),
    speakerName: entry.speakerName?.trim() || attendeeId || 'Không xác định',
    text: entry.text,
  };
}

function normalizeTranscriptParticipantId(attendeeId: string | undefined, id: number) {
  if (!attendeeId) return `transcript-${id}`;
  if (attendeeId.startsWith('account:')) return attendeeId.slice('account:'.length);
  if (attendeeId.startsWith('guest:')) return `guest-${attendeeId.slice('guest:'.length)}`;
  return attendeeId;
}

function mapListItem(item: MinutesListItemApiResponse): MeetingMinutesListItem {
  const participants = item.participantPreview.map(mapParticipantPreview);
  return {
    meetingId: String(item.meetingId),
    meetingCode: item.meetingCode ?? String(item.meetingId),
    title: item.title,
    date: item.date ?? '',
    startTime: timePart(item.startTime),
    endTime: timePart(item.endTime),
    chairmanName: accountName(item.chairman),
    secretaryName: 'Chưa xác định',
    participants,
    participantCount: item.participantCount,
    status: item.minutesStatus,
    currentVersion: item.currentVersion ?? undefined,
    updatedAt: item.updatedAt ?? undefined,
    myRole: item.myRole == null ? null : normalizeRole(item.myRole),
    canManage: item.canManage ?? false,
    durationLabel: '-',
    recordingSessionCount: 0,
    recordingStatus: 'COMPLETED',
    accessType: 'PRIVATE',
    meetingStatus: 'ENDED',
    content: emptyMinutesContent(),
    versions: [],
    transcript: [],
    documents: [],
  };
}

function mapVersion(version: MinutesVersionApiResponse): MeetingMinutesVersion {
  const sourceFallback =
    version.source === 'SYSTEM_GENERATED'
      ? 'Hệ thống tạo'
      : version.source === 'AI_GENERATED'
        ? 'AI tạo biên bản'
        : 'Chưa xác định';

  return {
    id: String(version.id),
    meetingId: String(version.meetingId),
    version: version.version,
    revision: version.revision,
    status: version.status,
    source: version.source,
    createdBy: accountName(version.createdBy, sourceFallback),
    createdAt: version.createdAt,
    updatedAt: version.updatedAt,
    finalizedAt: version.finalizedAt ?? undefined,
    content: contentJsonToText(version.content),
    contentJson: version.content,
  };
}

function mapMeetingDetail(
  meeting: BackendMeetingDetailResponse,
  versions: MeetingMinutesVersion[],
  documents: MeetingDocument[],
  transcript: MinutesTranscriptEntry[],
): MeetingMinutesListItem {
  const participants = meeting.participants.map(mapBackendParticipant);
  const chairman = participants.find((participant) => participant.role === 'CHAIRMAN');
  const secretary = participants.find((participant) => participant.role === 'SECRETARY');
  const latest = versions[0];
  return {
    meetingId: String(meeting.id),
    meetingCode: meeting.meetingCode ?? String(meeting.id),
    title: meeting.title,
    date: datePart(meeting.startTime),
    startTime: timePart(meeting.startTime),
    endTime: timePart(meeting.endTime),
    chairmanName: chairman?.name ?? 'Chưa xác định',
    secretaryName: secretary?.name ?? 'Chưa xác định',
    participants,
    participantCount: participants.length,
    status: latest?.status ?? 'NOT_GENERATED',
    currentVersion: latest?.version,
    updatedAt: latest?.updatedAt,
    myRole: null,
    canManage: meeting.canManage ?? false,
    durationLabel: durationLabel(meeting.startTime, meeting.endTime),
    recordingSessionCount: 0,
    recordingStatus: 'COMPLETED',
    accessType: meeting.accessType,
    meetingStatus: 'ENDED',
    content: contentJsonToMinutesContent(latest?.contentJson),
    versions,
    transcript,
    documents,
  };
}

function buildMinutesQuery(params: MinutesListParams) {
  const searchParams = new URLSearchParams();
  const { filters } = params;
  const query = filters.search.trim();
  if (query) searchParams.set('q', query);
  if (filters.status !== 'ALL') searchParams.set('status', filters.status);
  if (filters.role !== 'ALL') searchParams.set('role', filters.role);
  if (filters.time !== 'ALL') {
    const today = new Date();
    const to = today.toISOString().slice(0, 10);
    const fromDate = new Date(today);
    if (filters.time === 'LAST_7_DAYS') fromDate.setDate(today.getDate() - 7);
    if (filters.time === 'LAST_30_DAYS') fromDate.setDate(today.getDate() - 30);
    searchParams.set('from', filters.time === 'TODAY' ? to : fromDate.toISOString().slice(0, 10));
    searchParams.set('to', to);
  }
  searchParams.set('page', String(Math.max(0, params.page - 1)));
  searchParams.set('size', String(params.size));
  return searchParams.toString();
}

export async function getMinutes(
  params: MinutesListParams,
): Promise<MinutesPage<MeetingMinutesListItem>> {
  const query = buildMinutesQuery(params);
  const page = await managementFetchJson<MinutesPage<MinutesListItemApiResponse>>(
    `/api/minutes${query ? `?${query}` : ''}`,
  );
  return {
    ...page,
    content: page.content.map(mapListItem),
  };
}

export async function getMinutesSummary(): Promise<MinutesSummary> {
  return managementFetchJson<MinutesSummaryApiResponse>('/api/minutes/summary');
}

export async function getCurrentMinutes(meetingId: string): Promise<MeetingMinutesVersion | null> {
  try {
    const response = await managementFetchJson<MinutesVersionApiResponse>(
      `/api/meetings/${encodeURIComponent(meetingId)}/minutes/current`,
    );
    return mapVersion(response);
  } catch (error) {
    if (error instanceof ManagementApiError && error.status === 404) return null;
    throw error;
  }
}

export async function getMinutesVersions(meetingId: string): Promise<MeetingMinutesVersion[]> {
  const response = await managementFetchJson<MinutesVersionApiResponse[]>(
    `/api/meetings/${encodeURIComponent(meetingId)}/minutes/versions`,
  );
  return response.map(mapVersion);
}

export async function getMinutesTranscript(meetingId: string): Promise<MinutesTranscriptEntry[]> {
  const response = await managementFetchJson<MinutesTranscriptEntryApiResponse[]>(
    `/api/meetings/${encodeURIComponent(meetingId)}/minutes/transcript`,
  );
  return response.map(mapTranscriptEntry);
}

export async function getMinutesVersion(
  meetingId: string,
  version: number,
): Promise<MeetingMinutesVersion> {
  const response = await managementFetchJson<MinutesVersionApiResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/minutes/versions/${encodeURIComponent(version)}`,
  );
  return mapVersion(response);
}

export async function getMinutesDetail(meetingId: string): Promise<MinutesDetailData> {
  const [meeting, current, versions, documents, transcript] = await Promise.all([
    managementFetchJson<BackendMeetingDetailResponse>(
      `/api/meetings/${encodeURIComponent(meetingId)}`,
    ),
    getCurrentMinutes(meetingId),
    getMinutesVersions(meetingId),
    getMeetingDocuments(meetingId),
    getMinutesTranscript(meetingId),
  ]);
  const resolvedVersions = versions.length > 0 ? versions : current == null ? [] : [current];
  return {
    meeting: mapMeetingDetail(meeting, resolvedVersions, documents, transcript),
    current,
    versions: resolvedVersions,
  };
}

export async function generateMinutes(meetingId: string): Promise<MinutesProcessingApiResponse> {
  return managementFetchJson<MinutesProcessingApiResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/minutes/generate`,
    { method: 'POST' },
  );
}

export async function getMinutesProcessing(
  meetingId: string,
): Promise<MinutesProcessingApiResponse> {
  return managementFetchJson<MinutesProcessingApiResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/minutes/processing`,
  );
}

export async function retryMinutes(meetingId: string): Promise<MinutesProcessingApiResponse> {
  return managementFetchJson<MinutesProcessingApiResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/minutes/retry`,
    { method: 'POST' },
  );
}

export async function getMinutesTemplates(): Promise<MinutesTemplateApiResponse[]> {
  return managementFetchJson<MinutesTemplateApiResponse[]>('/api/minutes/templates');
}

export async function renderMinutesVersion(
  meetingId: string,
  version: number,
  templateCode: string,
): Promise<MinutesRenderApiResponse> {
  return managementFetchJson<MinutesRenderApiResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/minutes/versions/${encodeURIComponent(String(version))}/render`,
    {
      method: 'POST',
      body: JSON.stringify({ templateCode }),
    },
  );
}

export async function downloadMinutesPdf(
  meetingId: string,
  version: number,
  templateCode: string,
): Promise<MinutesPdfDownload> {
  const { blob, response } = await managementFetchBlob(
    `/api/meetings/${encodeURIComponent(meetingId)}/minutes/versions/${encodeURIComponent(String(version))}/export/pdf`,
    {
      method: 'POST',
      body: JSON.stringify({ templateCode }),
    },
  );
  const contentType = response.headers.get('content-type') ?? blob.type ?? '';

  if (!contentType.includes('application/pdf')) {
    throw new Error('Máy chủ không trả về tệp PDF hợp lệ.');
  }

  if (blob.size === 0) {
    throw new Error('Tệp PDF trả về rỗng.');
  }

  return {
    blob,
    filename:
      filenameFromContentDisposition(response.headers.get('content-disposition')) ??
      `bien-ban-hop-${meetingId}-v${version}.pdf`,
  };
}

export async function createMinutesVersion(
  meetingId: string,
  baseVersion: number,
  content: MinutesContentJson,
): Promise<MeetingMinutesVersion> {
  const response = await managementFetchJson<MinutesVersionApiResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/minutes/versions`,
    {
      method: 'POST',
      body: JSON.stringify({ baseVersion, content }),
    },
  );
  return mapVersion(response);
}

export async function updateMinutesDraft(
  meetingId: string,
  version: number,
  content: MinutesContentJson,
  expectedRevision: number,
): Promise<MeetingMinutesVersion> {
  if (!Number.isSafeInteger(expectedRevision) || expectedRevision < 0)
    throw new Error('Vui lòng tải lại phiên bản biên bản trước khi lưu bản nháp.');
  const response = await managementFetchJson<MinutesVersionApiResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/minutes/versions/${encodeURIComponent(version)}`,
    {
      method: 'PUT',
      body: JSON.stringify({ content, expectedRevision }),
    },
  );
  return mapVersion(response);
}

export async function finalizeMinutesVersion(
  meetingId: string,
  version: number,
): Promise<MeetingMinutesVersion> {
  const response = await managementFetchJson<MinutesVersionApiResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/minutes/versions/${encodeURIComponent(version)}/finalize`,
    { method: 'POST' },
  );
  return mapVersion(response);
}
