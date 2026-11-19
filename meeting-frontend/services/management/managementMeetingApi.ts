import {
  getMeetingDocuments as getSharedMeetingDocuments,
  uploadMeetingDocument as uploadSharedMeetingDocument,
} from '@/services/management/managementDocumentService';
import {
  managementFetchJson,
  managementFetchStatus,
  managementFetchVoid,
} from '@/services/management/httpClient';
import type {
  ManagementMeeting,
  ManagementMeetingDocument,
  ManagementMeetingListParams,
  ManagementMeetingMutationInput,
  ManagementMeetingParticipant,
  ManagementMeetingStatus,
} from '@/types/management/meeting';

interface BackendPageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

interface BackendMeetingResponse {
  id: number;
  meetingCode?: string;
  title: string;
  description?: string;
  roomName: string;
  roomSid?: string;
  meetingUrl?: string;
  startTime: string | null;
  endTime: string | null;
  status: ManagementMeeting['status'];
  accessType: ManagementMeeting['accessType'];
  creationMode?: ManagementMeeting['creationMode'];
  points?: ManagementMeeting['points'];
  pointsEditable?: boolean;
  canManage?: boolean;
}

interface BackendMeetingDetailResponse extends BackendMeetingResponse {
  participants: BackendMeetingParticipantResponse[];
}

interface BackendMeetingParticipantResponse {
  id?: number;
  participantKey: string;
  userId: string;
  fullName: string;
  email: string;
  role: ManagementMeetingParticipant['role'];
  status?: ManagementMeetingParticipant['status'];
  joinedAt?: string | null;
  leftAt?: string | null;
}

interface CreateMeetingPayload {
  title: string;
  description?: string;
  startTime?: string | null;
  endTime?: string | null;
  accessType: ManagementMeeting['accessType'];
  creationMode?: ManagementMeeting['creationMode'];
  pointNames?: string[];
  clientRequestId?: string;
  status?: ManagementMeeting['status'];
  participants: Array<{
    userPublicId: string;
    invitedEmail: string;
    fullName: string;
    role: ManagementMeetingParticipant['role'];
  }>;
}

interface UpdateMeetingPayload {
  title: string;
  description?: string;
  startTime?: string | null;
  endTime?: string | null;
  accessType: ManagementMeeting['accessType'];
  status?: ManagementMeeting['status'];
  pointNames?: string[];
  rosterBaseline?: Array<{ participantKey: string; role: ManagementMeetingParticipant['role'] }>;
  participants?: Array<{
    participantKey?: string;
    userPublicId?: string;
    invitedEmail?: string;
    fullName: string;
    role: ManagementMeetingParticipant['role'];
  }>;
}

interface ParticipantPayload {
  fullName: string;
  email: string;
  role: ManagementMeetingParticipant['role'];
}

function toUserPublicId(userId: string): string {
  const accountPrefix = 'account-';

  return userId.startsWith(accountPrefix) ? userId.slice(accountPrefix.length) : userId;
}

function mapParticipant(
  participant: BackendMeetingParticipantResponse,
): ManagementMeetingParticipant {
  return {
    id: participant.id ? String(participant.id) : undefined,
    participantKey: participant.participantKey,
    userId: participant.userId,
    name: participant.fullName,
    email: participant.email,
    role: participant.role,
    status: participant.status,
    joinedAt: participant.joinedAt,
    leftAt: participant.leftAt,
  };
}

function requireParticipantKey(participant: ManagementMeetingParticipant): string {
  const participantKey = participant.participantKey?.trim();
  if (!participantKey) {
    throw new Error('Không tìm thấy participantKey của người tham gia.');
  }
  return participantKey;
}

function mapMeetingBase(
  meeting: BackendMeetingResponse,
  participants: ManagementMeetingParticipant[] = [],
  documents: ManagementMeetingDocument[] = [],
): ManagementMeeting {
  return {
    id: String(meeting.id),
    meetingCode: meeting.meetingCode,
    title: meeting.title,
    description: meeting.description,
    roomName: meeting.roomName,
    roomSid: meeting.roomSid,
    meetingUrl: meeting.meetingUrl,
    startTime: meeting.startTime,
    endTime: meeting.endTime,
    status: meeting.status,
    accessType: meeting.accessType,
    creationMode: meeting.creationMode ?? 'SCHEDULED',
    ...(meeting.points ? { points: meeting.points } : {}),
    ...(meeting.pointsEditable != null ? { pointsEditable: meeting.pointsEditable } : {}),
    canManage: meeting.canManage ?? false,
    participants,
    documents,
  };
}

function toCreateMeetingPayload(input: ManagementMeetingMutationInput): CreateMeetingPayload {
  return {
    title: input.title,
    description: input.description,
    startTime: input.startTime,
    endTime: input.endTime,
    accessType: input.accessType,
    status: input.status,
    ...(input.creationMode ? { creationMode: input.creationMode } : {}),
    ...(input.pointNames ? { pointNames: input.pointNames.map((name) => name.trim()) } : {}),
    ...(input.clientRequestId ? { clientRequestId: input.clientRequestId } : {}),
    participants: input.participants.map((participant) => ({
      userPublicId: toUserPublicId(participant.userId),
      invitedEmail: participant.email,
      fullName: participant.name,
      role: participant.role,
    })),
  };
}

export function getPointMeetingCreationFingerprint(input: ManagementMeetingMutationInput): string {
  const payload = toCreateMeetingPayload({ ...input, clientRequestId: undefined });
  return JSON.stringify({
    ...payload,
    title: payload.title.trim(),
    participants: payload.participants
      .map((person) => ({
        ...person,
        userPublicId: person.userPublicId.trim(),
        invitedEmail: person.invitedEmail.trim().toLowerCase(),
        fullName: person.fullName.trim(),
      }))
      .sort((left, right) =>
        (left.userPublicId || left.invitedEmail).localeCompare(
          right.userPublicId || right.invitedEmail,
        ),
      ),
  });
}

export const getMeetingCreationFingerprint = getPointMeetingCreationFingerprint;

function toPointRosterPayload(
  participant: ManagementMeetingParticipant,
  existingParticipants: ManagementMeetingParticipant[],
): NonNullable<UpdateMeetingPayload['participants']>[number] {
  const existing =
    existingParticipants.find(
      (item) => item.participantKey && item.participantKey === participant.participantKey?.trim(),
    ) ??
    existingParticipants.find(
      (item) => toUserPublicId(item.userId) === toUserPublicId(participant.userId),
    );
  const participantKey = existing
    ? requireParticipantKey(existing)
    : participant.participantKey?.trim();
  if (participantKey) {
    return { participantKey, fullName: participant.name, role: participant.role };
  }
  return toParticipantCreatePayload(participant);
}

function toUpdateMeetingPayload(
  input: ManagementMeetingMutationInput,
  existingParticipants: ManagementMeetingParticipant[],
): UpdateMeetingPayload {
  return {
    title: input.title,
    description: input.description,
    startTime: input.startTime,
    endTime: input.endTime,
    accessType: input.accessType,
    status: input.status,
    ...(input.pointNames ? { pointNames: input.pointNames.map((name) => name.trim()) } : {}),
    participants: input.participants.map((participant) =>
      toPointRosterPayload(participant, existingParticipants),
    ),
    rosterBaseline: existingParticipants
      .filter((participant) => participant.status !== 'REMOVED')
      .map((participant) => ({
        participantKey: requireParticipantKey(participant),
        role: participant.role,
      })),
  };
}

function toParticipantCreatePayload(
  participant: ManagementMeetingParticipant,
): CreateMeetingPayload['participants'][number] {
  return {
    userPublicId: toUserPublicId(participant.userId),
    invitedEmail: participant.email,
    fullName: participant.name,
    role: participant.role,
  };
}

function toParticipantUpdatePayload(participant: ManagementMeetingParticipant): ParticipantPayload {
  return {
    fullName: participant.name,
    email: participant.email,
    role: participant.role,
  };
}

async function listParticipants(meetingId: string): Promise<ManagementMeetingParticipant[]> {
  const response = await managementFetchJson<BackendMeetingParticipantResponse[]>(
    `/api/meetings/${encodeURIComponent(meetingId)}/participants`,
  );
  return response.map(mapParticipant);
}

export async function listMeetingDocuments(
  meetingId: string,
): Promise<ManagementMeetingDocument[]> {
  const documents = await getSharedMeetingDocuments(meetingId);
  return documents.map((document) => ({
    ...document,
    name: document.originalFileName,
    size: document.fileSize,
    contentType: document.mimeType,
    createdAt: document.uploadedAt,
  }));
}

async function uploadMeetingDocument(meetingId: string, document: ManagementMeetingDocument) {
  if (!document.file) {
    return;
  }
  await uploadSharedMeetingDocument(meetingId, document.file);
}

async function deleteMeetingDocument(meetingId: string, documentId: string) {
  await managementFetchVoid(
    `/api/meetings/${encodeURIComponent(meetingId)}/documents/${encodeURIComponent(documentId)}`,
    {
      method: 'DELETE',
    },
  );
}

export async function addManagementParticipant(
  meetingId: string,
  participant: ManagementMeetingParticipant,
): Promise<ManagementMeetingParticipant> {
  const response = await managementFetchJson<BackendMeetingParticipantResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/participants`,
    {
      method: 'POST',
      body: JSON.stringify(toParticipantCreatePayload(participant)),
    },
  );

  return mapParticipant(response);
}

export async function updateManagementParticipant(
  meetingId: string,
  participantKey: string,
  participant: ManagementMeetingParticipant,
): Promise<ManagementMeetingParticipant> {
  const response = await managementFetchJson<BackendMeetingParticipantResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}/participants/${encodeURIComponent(participantKey)}`,
    {
      method: 'PUT',
      body: JSON.stringify(toParticipantUpdatePayload(participant)),
    },
  );

  return mapParticipant(response);
}

export async function deleteManagementParticipant(
  meetingId: string,
  participantKey: string,
): Promise<void> {
  await managementFetchVoid(
    `/api/meetings/${encodeURIComponent(meetingId)}/participants/${encodeURIComponent(participantKey)}`,
    {
      method: 'DELETE',
    },
  );
}

function buildListQuery(params?: ManagementMeetingListParams) {
  const searchParams = new URLSearchParams();

  if (params?.search) searchParams.set('search', params.search);
  if (params?.status && params.status !== 'ALL') searchParams.set('status', params.status);
  if (params?.accessType && params.accessType !== 'ALL')
    searchParams.set('accessType', params.accessType);
  searchParams.set('page', String(params?.page ?? 0));
  searchParams.set('size', String(params?.size ?? 20));
  searchParams.set('sort', params?.sort ?? 'startTime,desc');

  return searchParams.toString();
}

export async function listManagementMeetings(
  params?: ManagementMeetingListParams,
): Promise<ManagementMeeting[]> {
  const query = buildListQuery(params);
  const pageResponse = await managementFetchJson<BackendPageResponse<BackendMeetingResponse>>(
    `/api/meetings${query ? `?${query}` : ''}`,
  );

  return Promise.all(
    pageResponse.content.map(async (meeting) => {
      const participants = await listParticipants(String(meeting.id));
      return mapMeetingBase(meeting, participants, []);
    }),
  );
}

export async function getManagementMeetingById(meetingId: string): Promise<ManagementMeeting> {
  const [meeting, documents] = await Promise.all([
    managementFetchJson<BackendMeetingDetailResponse>(
      `/api/meetings/${encodeURIComponent(meetingId)}`,
    ),
    listMeetingDocuments(meetingId),
  ]);

  return mapMeetingBase(meeting, meeting.participants.map(mapParticipant), documents);
}

export async function createManagementMeeting(
  input: ManagementMeetingMutationInput,
): Promise<ManagementMeeting & { documentUploadFailed?: boolean }> {
  if (
    input.creationMode === 'POINT' &&
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      input.clientRequestId ?? '',
    )
  ) {
    throw new Error('Không thể xác nhận lần tạo cuộc họp. Vui lòng thử lại.');
  }
  const createdMeeting = await managementFetchJson<BackendMeetingResponse>('/api/meetings', {
    method: 'POST',
    body: JSON.stringify(toCreateMeetingPayload(input)),
  });

  const uploads = await Promise.allSettled(
    input.documents.map((document) => uploadMeetingDocument(String(createdMeeting.id), document)),
  );
  // A created meeting remains created even if the follow-up detail request fails.
  // Keep its link available so a retry cannot accidentally create a second meeting.
  const meeting = await getManagementMeetingById(String(createdMeeting.id)).catch(() =>
    mapMeetingBase(createdMeeting, input.participants, []),
  );
  return {
    ...meeting,
    documentUploadFailed: uploads.some((result) => result.status === 'rejected'),
  };
}

export async function updateManagementMeeting(
  meetingId: string,
  input: ManagementMeetingMutationInput,
  existingParticipants: ManagementMeetingParticipant[],
  existingDocuments: ManagementMeetingDocument[],
): Promise<ManagementMeeting> {
  // Validate all persisted keys before the first write, including removed rows.
  existingParticipants.forEach(requireParticipantKey);
  await managementFetchJson<BackendMeetingResponse>(
    `/api/meetings/${encodeURIComponent(meetingId)}`,
    {
      method: 'PUT',
      body: JSON.stringify(toUpdateMeetingPayload(input, existingParticipants)),
    },
  );

  const retainedDocumentIds = new Set(
    input.documents.filter((document) => !document.file).map((document) => document.id),
  );
  const removedDocuments = existingDocuments.filter(
    (document) => !retainedDocumentIds.has(document.id),
  );
  const newDocuments = input.documents.filter((document) => document.file);

  const results = await Promise.allSettled([
    ...removedDocuments.map((document) => deleteMeetingDocument(meetingId, document.id)),
    ...newDocuments.map((document) => uploadMeetingDocument(meetingId, document)),
  ]);

  const saved = await getManagementMeetingById(meetingId).catch(() => null);
  if (results.some((result) => result.status === 'rejected'))
    throw new MeetingPartialSaveError(
      'Thông tin và người tham gia đã được lưu; một số tài liệu chưa cập nhật. Hãy tải lại để kiểm tra trước khi thử lại.',
      saved,
    );
  if (!saved)
    throw new MeetingPartialSaveError(
      'Thông tin cuộc họp đã được lưu nhưng chưa tải lại được dữ liệu. Hãy tải lại để kiểm tra.',
      null,
    );
  return saved;
}

export class MeetingPartialSaveError extends Error {
  constructor(
    message: string,
    public readonly savedMeeting: ManagementMeeting | null,
  ) {
    super(message);
    this.name = 'MeetingPartialSaveError';
  }
}

export async function overrideManagementMeeting(
  meetingId: string,
  input: {
    startTime?: string;
    endTime?: string;
    status?: Exclude<ManagementMeetingStatus, 'COMPLETED'>;
    reason?: string;
  },
): Promise<boolean> {
  const response = await managementFetchStatus(
    `/api/admin/meeting-tools/meetings/${encodeURIComponent(meetingId)}/override`,
    {
      method: 'PATCH',
      body: JSON.stringify(input),
    },
  );
  return response.status === 202 || response.headers.get('X-Meeting-Cleanup-Pending') === 'true';
}

export async function endManagementMeeting(meetingId: string): Promise<boolean> {
  const response = await managementFetchStatus(
    `/api/meetings/${encodeURIComponent(meetingId)}/end`,
    {
      method: 'POST',
    },
  );
  return response.status === 202 || response.headers.get('X-Meeting-Cleanup-Pending') === 'true';
}

export async function resetManagementMeetingMinutes(meetingId: string): Promise<void> {
  await managementFetchVoid(
    `/api/admin/meeting-tools/meetings/${encodeURIComponent(meetingId)}/reset-minutes`,
    {
      method: 'POST',
    },
  );
}

export async function deleteManagementMeeting(meetingId: string): Promise<boolean> {
  const response = await managementFetchStatus(`/api/meetings/${encodeURIComponent(meetingId)}`, {
    method: 'DELETE',
  });
  return response.status === 202 || response.headers.get('X-Meeting-Cleanup-Pending') === 'true';
}
