import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  managementFetchJson,
  managementFetchStatus,
  managementFetchVoid,
} from '@/services/management/httpClient';
import {
  addManagementParticipant,
  createManagementMeeting,
  getPointMeetingCreationFingerprint,
  deleteManagementParticipant,
  getManagementMeetingById,
  listManagementMeetings,
  overrideManagementMeeting,
  updateManagementMeeting,
  updateManagementParticipant,
  MeetingPartialSaveError,
} from './managementMeetingApi';
import { uploadMeetingDocument } from './managementDocumentService';
import type {
  ManagementMeetingDocument,
  ManagementMeetingMutationInput,
  ManagementMeetingParticipant,
} from '@/types/management/meeting';

vi.mock('@/services/management/httpClient', () => ({
  managementFetchJson: vi.fn(),
  managementFetchStatus: vi.fn(),
  managementFetchVoid: vi.fn(),
}));

vi.mock('@/services/management/managementDocumentService', () => ({
  getMeetingDocuments: vi.fn().mockResolvedValue([]),
  uploadMeetingDocument: vi.fn(),
}));

const mockedFetchJson = vi.mocked(managementFetchJson);
const mockedFetchVoid = vi.mocked(managementFetchVoid);
const mockedFetchStatus = vi.mocked(managementFetchStatus);

describe('admin override cleanup receipts', () => {
  beforeEach(() => mockedFetchStatus.mockReset());

  it.each([
    { status: 204, pendingHeader: null, pending: false },
    { status: 202, pendingHeader: null, pending: true },
    { status: 204, pendingHeader: 'true', pending: true },
  ])('preserves $status cleanupPending=$pending', async ({ status, pendingHeader, pending }) => {
    const headers = new Headers();
    if (pendingHeader) headers.set('X-Meeting-Cleanup-Pending', pendingHeader);
    mockedFetchStatus.mockResolvedValueOnce({ status, headers });
    const input = { status: 'CANCELLED' as const, reason: 'Cancel meeting' };

    expect(await overrideManagementMeeting('12', input)).toBe(pending);
    expect(mockedFetchStatus).toHaveBeenCalledExactlyOnceWith(
      '/api/admin/meeting-tools/meetings/12/override',
      { method: 'PATCH', body: JSON.stringify(input) },
    );
  });

  it('preserves rejected overrides instead of reporting a successful pending cleanup', async () => {
    const conflict = new Error('Old room cleanup still pending');
    mockedFetchStatus.mockRejectedValueOnce(conflict);
    await expect(
      overrideManagementMeeting('12', { status: 'IN_PROGRESS', reason: 'Reopen' }),
    ).rejects.toBe(conflict);
  });
});

describe('managementMeetingApi participantKey contract', () => {
  beforeEach(() => {
    mockedFetchJson.mockReset();
    mockedFetchVoid.mockReset();
  });

  it('includes named public members in the create payload alongside chairman and secretary', async () => {
    const participants = [
      participantInput({
        userId: 'account-chair',
        role: 'CHAIRMAN',
        email: 'chair@example.invalid',
      }),
      participantInput({
        userId: 'account-secretary',
        role: 'SECRETARY',
        email: 'sec@example.invalid',
      }),
      participantInput({
        userId: 'account-member',
        role: 'MEMBER',
        email: 'member@example.invalid',
      }),
    ];
    mockedFetchJson.mockResolvedValueOnce(meetingListResponse()).mockResolvedValueOnce({
      ...meetingListResponse(),
      accessType: 'PUBLIC',
      participants: participants.map((p, index) =>
        participantResponse({
          participantKey: `created-${index}`,
          userId: p.userId,
          role: p.role,
          email: p.email,
        }),
      ),
    });

    const created = await createManagementMeeting(
      mutationInput({ accessType: 'PUBLIC', participants }),
    );
    expect(mockedFetchJson).toHaveBeenNthCalledWith(1, '/api/meetings', {
      method: 'POST',
      body: JSON.stringify({
        title: 'Meeting',
        description: 'Description',
        startTime: '2026-09-08T08:00:00',
        endTime: '2026-09-08T09:00:00',
        accessType: 'PUBLIC',
        participants: participants.map((p) => ({
          userPublicId: p.userId.slice('account-'.length),
          invitedEmail: p.email,
          fullName: p.name,
          role: p.role,
        })),
      }),
    });
    expect(created.participants.map((p) => p.role)).toEqual(['CHAIRMAN', 'SECRETARY', 'MEMBER']);
  });

  it('maps participantKey from meeting detail participants', async () => {
    mockedFetchJson.mockResolvedValue(meetingDetailResponse());

    const meeting = await getManagementMeetingById('12');

    expect(meeting.participants[0].participantKey).toBe('pk-123');
  });

  it('preserves participantKey when loading participants for meeting list items', async () => {
    mockedFetchJson
      .mockResolvedValueOnce({
        content: [meetingListResponse()],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      })
      .mockResolvedValueOnce([participantResponse({ participantKey: 'pk-list' })]);

    const meetings = await listManagementMeetings();

    expect(meetings[0].participants[0].participantKey).toBe('pk-list');
  });

  it('preserves participantKey from add participant response', async () => {
    mockedFetchJson.mockResolvedValue(participantResponse({ participantKey: 'pk-added' }));

    const participant = await addManagementParticipant('12', participantInput());

    expect(participant.participantKey).toBe('pk-added');
  });

  it('updates participants by participantKey instead of userId', async () => {
    mockedFetchJson.mockResolvedValue(participantResponse({ participantKey: 'pk-123' }));

    await updateManagementParticipant(
      '12',
      'pk-123',
      participantInput({ userId: 'account-user-1' }),
    );

    expect(mockedFetchJson).toHaveBeenCalledWith('/api/meetings/12/participants/pk-123', {
      method: 'PUT',
      body: JSON.stringify({
        fullName: 'User One',
        email: 'user1@cusc.vn',
        role: 'MEMBER',
      }),
    });
    expect(mockedFetchJson.mock.calls[0][0]).not.toContain('account-user-1');
  });

  it('encodes participantKey when updating participants', async () => {
    mockedFetchJson.mockResolvedValue(participantResponse());

    await updateManagementParticipant('meeting/12', 'pk/123', participantInput());

    expect(mockedFetchJson).toHaveBeenCalledWith(
      '/api/meetings/meeting%2F12/participants/pk%2F123',
      expect.any(Object),
    );
  });

  it('deletes participants by participantKey', async () => {
    mockedFetchVoid.mockResolvedValue(undefined);

    await deleteManagementParticipant('12', 'pk-123');

    expect(mockedFetchVoid).toHaveBeenCalledWith('/api/meetings/12/participants/pk-123', {
      method: 'DELETE',
    });
  });

  it('encodes participantKey when deleting participants', async () => {
    mockedFetchVoid.mockResolvedValue(undefined);

    await deleteManagementParticipant('meeting/12', 'pk/123');

    expect(mockedFetchVoid).toHaveBeenCalledWith(
      '/api/meetings/meeting%2F12/participants/pk%2F123',
      expect.any(Object),
    );
  });

  it('commits an existing role change in the atomic roster using its persisted key', async () => {
    mockedFetchJson
      .mockResolvedValueOnce(meetingListResponse())
      .mockResolvedValueOnce(meetingDetailResponse());

    await updateManagementMeeting(
      '12',
      mutationInput({
        participants: [
          participantInput({
            participantKey: undefined,
            userId: 'account-user-1',
            role: 'CHAIRMAN',
          }),
        ],
      }),
      [participantInput({ participantKey: 'pk-update', userId: 'account-user-1', role: 'MEMBER' })],
      [],
    );

    expect(mockedFetchJson).toHaveBeenCalledTimes(2);
    const body = JSON.parse(String(mockedFetchJson.mock.calls[0][1]?.body));
    expect(body.participants).toEqual([
      { participantKey: 'pk-update', fullName: 'User One', role: 'CHAIRMAN' },
    ]);
    expect(body.rosterBaseline).toEqual([{ participantKey: 'pk-update', role: 'MEMBER' }]);
    expect(mockedFetchVoid).not.toHaveBeenCalled();
  });

  it('sync removals use the existing persisted participantKey instead of userId', async () => {
    mockedFetchJson
      .mockResolvedValueOnce(meetingListResponse())
      .mockResolvedValueOnce(meetingDetailResponse());
    mockedFetchVoid.mockResolvedValue(undefined);

    await updateManagementMeeting(
      '12',
      mutationInput({ participants: [] }),
      [participantInput({ participantKey: 'pk-remove', userId: 'account-user-2' })],
      [],
    );

    const body = JSON.parse(String(mockedFetchJson.mock.calls[0][1]?.body));
    expect(body.participants).toEqual([]);
    expect(body.rosterBaseline).toEqual([{ participantKey: 'pk-remove', role: 'MEMBER' }]);
    expect(mockedFetchVoid).not.toHaveBeenCalled();
  });

  it('sync additions do not require participantKey before backend persistence', async () => {
    mockedFetchJson
      .mockResolvedValueOnce(meetingListResponse())
      .mockResolvedValueOnce(meetingDetailResponse());

    await updateManagementMeeting(
      '12',
      mutationInput({ participants: [participantInput({ participantKey: undefined })] }),
      [],
      [],
    );

    const body = JSON.parse(String(mockedFetchJson.mock.calls[0][1]?.body));
    expect(body.participants).toEqual([
      {
        userPublicId: 'user-1',
        invitedEmail: 'user1@cusc.vn',
        fullName: 'User One',
        role: 'MEMBER',
      },
    ]);
    expect(mockedFetchJson.mock.calls.map((call) => call[0])).toEqual([
      '/api/meetings/12',
      '/api/meetings/12',
    ]);
  });

  it('keeps joined public guests unchanged when only meeting information is saved', async () => {
    const guest = participantInput({
      participantKey: 'guest-key',
      userId: 'guest-123',
      name: 'Khách đã vào',
      email: 'guest-123@guest.local',
      role: 'GUEST',
      status: 'JOINED',
      joinedAt: '2026-10-05T09:00:00',
    });
    const original = [participantInput({ role: 'CHAIRMAN' }), guest];
    mockedFetchJson.mockResolvedValueOnce(meetingListResponse()).mockResolvedValueOnce({
      ...meetingListResponse(),
      accessType: 'PUBLIC',
      participants: [
        participantResponse({ role: 'CHAIRMAN' }),
        participantResponse({
          participantKey: 'guest-key',
          userId: 'guest-123',
          fullName: guest.name,
          email: guest.email,
          role: 'GUEST',
          status: 'JOINED',
          joinedAt: guest.joinedAt!,
        }),
      ],
    });

    const saved = await updateManagementMeeting(
      '12',
      mutationInput({
        accessType: 'PUBLIC',
        participants: original.map((p) => ({ ...p })),
      }),
      original,
      [],
    );

    expect(mockedFetchJson).toHaveBeenCalledTimes(2);
    expect(mockedFetchVoid).not.toHaveBeenCalled();
    expect(saved.participants[1]).toMatchObject({
      participantKey: 'guest-key',
      role: 'GUEST',
      status: 'JOINED',
      joinedAt: guest.joinedAt,
    });
  });

  it('adds a public meeting member without changing the secretary or joined guest', async () => {
    const chairman = participantInput({ role: 'CHAIRMAN' });
    const secretary = participantInput({
      participantKey: 'secretary-key',
      userId: 'account-secretary',
      email: 'secretary@cusc.vn',
      role: 'SECRETARY',
    });
    const guest = participantInput({
      participantKey: 'guest-key',
      userId: 'guest-123',
      email: 'guest-123@guest.local',
      role: 'GUEST',
      status: 'JOINED',
    });
    const member = participantInput({
      participantKey: undefined,
      userId: 'new-member',
      name: 'New Member',
      email: 'member@cusc.vn',
      role: 'MEMBER',
    });
    mockedFetchJson.mockResolvedValueOnce(meetingListResponse()).mockResolvedValueOnce({
      ...meetingListResponse(),
      accessType: 'PUBLIC',
      participants: [
        participantResponse({ role: 'CHAIRMAN' }),
        participantResponse({
          participantKey: secretary.participantKey,
          userId: secretary.userId,
          role: secretary.role,
        }),
        participantResponse({
          participantKey: guest.participantKey,
          userId: guest.userId,
          role: guest.role,
          status: 'JOINED',
        }),
        participantResponse({
          participantKey: 'member-key',
          userId: 'account-new-member',
          role: 'MEMBER',
        }),
      ],
    });
    const saved = await updateManagementMeeting(
      '12',
      mutationInput({ accessType: 'PUBLIC', participants: [chairman, secretary, guest, member] }),
      [chairman, secretary, guest],
      [],
    );
    expect(mockedFetchJson).toHaveBeenCalledTimes(2);
    const body = JSON.parse(String(mockedFetchJson.mock.calls[0][1]?.body));
    expect(body.participants.at(-1)).toEqual({
      userPublicId: 'new-member',
      invitedEmail: 'member@cusc.vn',
      fullName: 'New Member',
      role: 'MEMBER',
    });
    expect(mockedFetchVoid).not.toHaveBeenCalled();
    expect(saved.participants.map((participant) => participant.role)).toEqual([
      'CHAIRMAN',
      'SECRETARY',
      'GUEST',
      'MEMBER',
    ]);
    expect(saved.participants[2]).toMatchObject({ participantKey: 'guest-key', status: 'JOINED' });
  });

  it('preserves guests who join after the edit form was opened', async () => {
    const chairman = participantInput({ role: 'CHAIRMAN' });
    mockedFetchJson.mockResolvedValueOnce(meetingListResponse()).mockResolvedValueOnce({
      ...meetingListResponse(),
      accessType: 'PUBLIC',
      participants: [
        participantResponse({ role: 'CHAIRMAN' }),
        participantResponse({
          participantKey: 'late-guest',
          userId: 'guest-late',
          role: 'GUEST',
          status: 'JOINED',
        }),
      ],
    });
    const saved = await updateManagementMeeting(
      '12',
      mutationInput({
        accessType: 'PUBLIC',
        participants: [chairman],
      }),
      [chairman],
      [],
    );
    expect(mockedFetchJson).toHaveBeenCalledTimes(2);
    expect(mockedFetchVoid).not.toHaveBeenCalled();
    expect(saved.participants).toHaveLength(2);
    expect(saved.participants[1].participantKey).toBe('late-guest');
  });

  it('sync update fails clearly before mutation when existing participantKey is missing', async () => {
    mockedFetchJson.mockResolvedValueOnce(meetingListResponse());

    await expect(
      updateManagementMeeting(
        '12',
        mutationInput({ participants: [participantInput({ role: 'CHAIRMAN' })] }),
        [participantInput({ participantKey: ' ' })],
        [],
      ),
    ).rejects.toThrow('Không tìm thấy participantKey của người tham gia.');

    expect(mockedFetchJson).not.toHaveBeenCalled();
    expect(mockedFetchVoid).not.toHaveBeenCalled();
  });

  it('sync removal fails clearly before mutation when existing participantKey is missing', async () => {
    mockedFetchJson.mockResolvedValueOnce(meetingListResponse());

    await expect(
      updateManagementMeeting(
        '12',
        mutationInput({ participants: [] }),
        [participantInput({ participantKey: undefined })],
        [],
      ),
    ).rejects.toThrow('Không tìm thấy participantKey của người tham gia.');

    expect(mockedFetchJson).not.toHaveBeenCalled();
    expect(mockedFetchVoid).not.toHaveBeenCalled();
  });
});

describe('production save receipts and partial documents', () => {
  beforeEach(() => {
    mockedFetchJson.mockReset();
    mockedFetchVoid.mockReset();
    vi.mocked(uploadMeetingDocument).mockReset();
  });

  it.each(['SCHEDULED', 'LATER', 'INSTANT'] as const)(
    'keeps the %s request ID when POST acknowledgement was lost',
    async (creationMode) => {
      const input = mutationInput({
        creationMode,
        clientRequestId: '22222222-2222-4222-8222-222222222222',
        accessType: creationMode === 'SCHEDULED' ? 'PRIVATE' : 'PUBLIC',
      });
      mockedFetchJson
        .mockRejectedValueOnce(new Error('Lost response'))
        .mockResolvedValueOnce(meetingListResponse())
        .mockRejectedValueOnce(new Error('Detail unavailable'));
      await expect(createManagementMeeting(input)).rejects.toThrow('Lost response');
      const recovered = await createManagementMeeting(input);
      expect(recovered.id).toBe('12');
      expect(mockedFetchJson.mock.calls[0][1]?.body).toBe(mockedFetchJson.mock.calls[1][1]?.body);
      expect(JSON.parse(String(mockedFetchJson.mock.calls[1][1]?.body)).clientRequestId).toBe(
        input.clientRequestId,
      );
    },
  );
  it('retains a successful metadata/roster receipt while reporting failed attachment updates truthfully', async () => {
    mockedFetchJson
      .mockResolvedValueOnce(meetingListResponse())
      .mockResolvedValueOnce(meetingDetailResponse());
    vi.mocked(uploadMeetingDocument).mockRejectedValueOnce(new Error('Upload unavailable'));
    const error = await updateManagementMeeting(
      '12',
      mutationInput({ documents: [{ id: 'local', name: 'file.pdf', file: {} as File }] }),
      [participantInput()],
      [],
    ).catch((failure) => failure);
    expect(error).toBeInstanceOf(MeetingPartialSaveError);
    expect(error.savedMeeting.id).toBe('12');
    expect(error.message).toContain('đã được lưu');
    expect(mockedFetchJson.mock.calls.filter((call) => call[1]?.method === 'PUT')).toHaveLength(1);
    expect(mockedFetchVoid).not.toHaveBeenCalled();
  });
  it('does not touch files if the atomic roster write conflicts', async () => {
    mockedFetchJson.mockRejectedValueOnce(new Error('MEETING_ROSTER_CHANGED'));
    await expect(
      updateManagementMeeting(
        '12',
        mutationInput({ documents: [{ id: 'new', name: 'file.pdf', file: {} as File }] }),
        [participantInput()],
        [{ id: 'old', name: 'old.pdf' }],
      ),
    ).rejects.toThrow('MEETING_ROSTER_CHANGED');
    expect(vi.mocked(uploadMeetingDocument)).not.toHaveBeenCalled();
    expect(mockedFetchVoid).not.toHaveBeenCalled();
  });
});

function participantInput(
  overrides: Partial<ManagementMeetingParticipant> = {},
): ManagementMeetingParticipant {
  return {
    id: '1',
    participantKey: 'pk-123',
    userId: 'account-user-1',
    name: 'User One',
    email: 'user1@cusc.vn',
    role: 'MEMBER',
    status: 'INVITED',
    joinedAt: null,
    leftAt: null,
    ...overrides,
  };
}

function participantResponse(
  overrides: Partial<{
    id: number;
    participantKey: string;
    userId: string;
    fullName: string;
    email: string;
    role: ManagementMeetingParticipant['role'];
    status: ManagementMeetingParticipant['status'];
    joinedAt: string | null;
    leftAt: string | null;
  }> = {},
) {
  return {
    id: 1,
    participantKey: 'pk-123',
    userId: 'account-user-1',
    fullName: 'User One',
    email: 'user1@cusc.vn',
    role: 'MEMBER',
    status: 'INVITED',
    joinedAt: null,
    leftAt: null,
    ...overrides,
  };
}

function meetingListResponse() {
  return {
    id: 12,
    meetingCode: 'MTG-12',
    title: 'Meeting',
    roomName: 'room-12',
    startTime: '2026-09-08T08:00:00',
    endTime: '2026-09-08T09:00:00',
    status: 'UPCOMING',
    accessType: 'PRIVATE',
  };
}

function meetingDetailResponse() {
  return {
    ...meetingListResponse(),
    participants: [participantResponse()],
  };
}

function mutationInput(
  overrides: Partial<ManagementMeetingMutationInput> = {},
): ManagementMeetingMutationInput {
  return {
    title: 'Meeting',
    description: 'Description',
    startTime: '2026-09-08T08:00:00',
    endTime: '2026-09-08T09:00:00',
    accessType: 'PRIVATE',
    participants: [participantInput()],
    documents: [] as ManagementMeetingDocument[],
    ...overrides,
  };
}

describe('POINT meeting payloads and safe creation result', () => {
  beforeEach(() => {
    mockedFetchJson.mockReset();
    mockedFetchVoid.mockReset();
  });

  const pointResponse = () => ({
    ...meetingListResponse(),
    accessType: 'PUBLIC',
    creationMode: 'POINT',
    points: [
      { id: 31, name: 'Trường A' },
      { id: 32, name: 'Trường B' },
    ],
    pointsEditable: true,
  });
  const clientRequestId = '11111111-1111-4111-8111-111111111111';

  it('creates a scheduled public POINT meeting without requiring designated participants', async () => {
    mockedFetchJson
      .mockResolvedValueOnce(pointResponse())
      .mockResolvedValueOnce({ ...pointResponse(), participants: [] });
    const created = await createManagementMeeting(
      mutationInput({
        accessType: 'PUBLIC',
        creationMode: 'POINT',
        clientRequestId,
        pointNames: [' Trường A ', 'Trường B'],
        participants: [],
      }),
    );
    const body = JSON.parse(String(mockedFetchJson.mock.calls[0][1]?.body));
    expect(body).toMatchObject({
      accessType: 'PUBLIC',
      creationMode: 'POINT',
      clientRequestId,
      pointNames: ['Trường A', 'Trường B'],
      participants: [],
      startTime: '2026-09-08T08:00:00',
      endTime: '2026-09-08T09:00:00',
    });
    expect(created.points).toEqual(pointResponse().points);
    expect(created.pointsEditable).toBe(true);
  });

  it('includes point names in a POINT edit and preserves server point IDs in the saved result', async () => {
    mockedFetchJson
      .mockResolvedValueOnce(pointResponse())
      .mockResolvedValueOnce({ ...pointResponse(), participants: [] });
    const saved = await updateManagementMeeting(
      '12',
      mutationInput({
        accessType: 'PUBLIC',
        creationMode: 'POINT',
        pointNames: [' Trường A ', 'Trường B'],
        participants: [],
      }),
      [],
      [],
    );
    const body = JSON.parse(String(mockedFetchJson.mock.calls[0][1]?.body));
    expect(body.pointNames).toEqual(['Trường A', 'Trường B']);
    expect(body).not.toHaveProperty('points');
    expect(body).not.toHaveProperty('creationMode');
    expect(body.participants).toEqual([]);
    expect(saved.points?.[0].id).toBe(31);
  });

  it('commits POINT metadata and a chair-secretary swap in one PUT without sequential participant mutations', async () => {
    const chair = participantInput({
      participantKey: 'chair-key',
      userId: 'account-chair',
      role: 'CHAIRMAN',
    });
    const secretary = participantInput({
      participantKey: 'secretary-key',
      userId: 'account-secretary',
      role: 'SECRETARY',
    });
    const guest = participantInput({
      participantKey: 'guest-key',
      userId: 'guest-visitor',
      role: 'GUEST',
      email: '',
      status: 'JOINED',
    });
    const addition = participantInput({
      participantKey: undefined,
      id: undefined,
      userId: 'new-user',
    });
    mockedFetchJson
      .mockResolvedValueOnce(pointResponse())
      .mockResolvedValueOnce({ ...pointResponse(), participants: [] });
    await updateManagementMeeting(
      '12',
      mutationInput({
        title: 'Updated title',
        accessType: 'PUBLIC',
        creationMode: 'POINT',
        pointNames: ['Trường A'],
        participants: [
          { ...chair, role: 'SECRETARY' },
          { ...secretary, role: 'CHAIRMAN' },
          guest,
          addition,
        ],
      }),
      [chair, secretary, guest],
      [],
    );
    const mutations = mockedFetchJson.mock.calls.filter((call) => call[1]?.method === 'PUT');
    expect(mutations).toHaveLength(1);
    expect(mockedFetchJson.mock.calls.map((call) => call[0])).toEqual([
      '/api/meetings/12',
      '/api/meetings/12',
    ]);
    const body = JSON.parse(String(mutations[0][1]?.body));
    expect(body.title).toBe('Updated title');
    expect(body.participants).toEqual([
      { participantKey: 'chair-key', fullName: chair.name, role: 'SECRETARY' },
      { participantKey: 'secretary-key', fullName: secretary.name, role: 'CHAIRMAN' },
      { participantKey: 'guest-key', fullName: guest.name, role: 'GUEST' },
      {
        userPublicId: 'new-user',
        invitedEmail: addition.email,
        fullName: addition.name,
        role: 'MEMBER',
      },
    ]);
    expect(mockedFetchVoid).not.toHaveBeenCalled();
  });

  it('does not mutate individual POINT roles or documents after the atomic PUT fails', async () => {
    mockedFetchJson.mockRejectedValueOnce(new Error('Invalid roster'));
    const oldChair = participantInput({ participantKey: 'old-chair', role: 'CHAIRMAN' });
    await expect(
      updateManagementMeeting(
        '12',
        mutationInput({ creationMode: 'POINT', accessType: 'PUBLIC', participants: [] }),
        [oldChair],
        [{ id: 'old-document', name: 'Old document' }],
      ),
    ).rejects.toThrow('Invalid roster');
    expect(mockedFetchJson).toHaveBeenCalledTimes(1);
    expect(mockedFetchVoid).not.toHaveBeenCalled();
    expect(JSON.parse(String(mockedFetchJson.mock.calls[0][1]?.body)).participants).toEqual([]);
  });

  it('resolves an existing POINT account key before PUT when a selected account only contains its public ID', async () => {
    const existing = participantInput({
      participantKey: 'persisted-account',
      userId: 'account-user-1',
    });
    mockedFetchJson
      .mockResolvedValueOnce(pointResponse())
      .mockResolvedValueOnce({ ...pointResponse(), participants: [] });
    await updateManagementMeeting(
      '12',
      mutationInput({
        creationMode: 'POINT',
        accessType: 'PUBLIC',
        participants: [
          participantInput({ participantKey: undefined, userId: 'user-1', role: 'CHAIRMAN' }),
        ],
      }),
      [existing],
      [],
    );
    const body = JSON.parse(String(mockedFetchJson.mock.calls[0][1]?.body));
    expect(body.participants).toEqual([
      { participantKey: 'persisted-account', fullName: existing.name, role: 'CHAIRMAN' },
    ]);
  });

  it('fails before POINT metadata mutation if an existing selected participant has no persisted key', async () => {
    const existing = participantInput({ participantKey: undefined });
    await expect(
      updateManagementMeeting(
        '12',
        mutationInput({
          creationMode: 'POINT',
          accessType: 'PUBLIC',
          participants: [{ ...existing, role: 'CHAIRMAN' }],
        }),
        [existing],
        [],
      ),
    ).rejects.toThrow('Không tìm thấy participantKey');
    expect(mockedFetchJson).not.toHaveBeenCalled();
    expect(mockedFetchVoid).not.toHaveBeenCalled();
  });

  it('keeps a successful create result and its ID if only the follow-up detail load fails', async () => {
    mockedFetchJson
      .mockResolvedValueOnce(pointResponse())
      .mockRejectedValueOnce(new Error('Detail unavailable'));
    const created = await createManagementMeeting(
      mutationInput({
        accessType: 'PUBLIC',
        creationMode: 'POINT',
        clientRequestId,
        pointNames: ['Trường A', 'Trường B'],
        participants: [],
      }),
    );
    expect(created.id).toBe('12');
    expect(created.creationMode).toBe('POINT');
    expect(created.points?.[0].id).toBe(31);
    expect(mockedFetchJson.mock.calls.filter((call) => call[1]?.method === 'POST')).toHaveLength(1);
  });

  it('does not add point fields to an ordinary create', async () => {
    mockedFetchJson
      .mockResolvedValueOnce(meetingListResponse())
      .mockResolvedValueOnce(meetingDetailResponse());
    await createManagementMeeting(mutationInput());
    const body = JSON.parse(String(mockedFetchJson.mock.calls[0][1]?.body));
    expect(body).not.toHaveProperty('pointNames');
    expect(body).not.toHaveProperty('creationMode');
    expect(body).not.toHaveProperty('clientRequestId');
  });

  it('reuses the supplied POINT creation key after a lost POST response without changing its body', async () => {
    const input = mutationInput({
      creationMode: 'POINT',
      accessType: 'PUBLIC',
      pointNames: ['Trường A'],
      clientRequestId,
      participants: [],
    });
    mockedFetchJson.mockRejectedValueOnce(new Error('Response lost'));
    await expect(createManagementMeeting(input)).rejects.toThrow('Response lost');
    mockedFetchJson
      .mockResolvedValueOnce(pointResponse())
      .mockResolvedValueOnce({ ...pointResponse(), participants: [] });
    expect((await createManagementMeeting(input)).id).toBe('12');
    const posts = mockedFetchJson.mock.calls.filter((call) => call[1]?.method === 'POST');
    expect(posts).toHaveLength(2);
    expect(posts[0][1]?.body).toBe(posts[1][1]?.body);
    expect(JSON.parse(String(posts[1][1]?.body)).clientRequestId).toBe(clientRequestId);
  });

  it('refuses an untracked POINT create before any network mutation', async () => {
    await expect(
      createManagementMeeting(
        mutationInput({ creationMode: 'POINT', accessType: 'PUBLIC', pointNames: ['Trường A'] }),
      ),
    ).rejects.toThrow('xác nhận');
    expect(mockedFetchJson).not.toHaveBeenCalled();
  });

  it('fingerprints only the serialized creation metadata and roster, excluding request key and documents', () => {
    const input = mutationInput({
      creationMode: 'POINT',
      accessType: 'PUBLIC',
      pointNames: [' Trường A '],
    });
    const original = getPointMeetingCreationFingerprint(input);
    expect(
      getPointMeetingCreationFingerprint({
        ...input,
        clientRequestId,
        documents: [{ id: 'doc', name: 'Document' }],
        pointNames: ['Trường A'],
      }),
    ).toBe(original);
    expect(getPointMeetingCreationFingerprint({ ...input, title: 'Changed title' })).not.toBe(
      original,
    );
    expect(getPointMeetingCreationFingerprint({ ...input, participants: [] })).not.toBe(original);
    expect(getPointMeetingCreationFingerprint({ ...input, pointNames: ['Trường B'] })).not.toBe(
      original,
    );
  });

  it('keeps the creation fingerprint for equivalent trimmed names and reordered selected accounts', () => {
    const first = participantInput({
      userId: 'account-first',
      email: 'First@example.test',
      name: ' First ',
    });
    const second = participantInput({
      userId: 'account-second',
      email: 'second@example.test',
      name: 'Second',
    });
    const input = mutationInput({
      title: ' Meeting ',
      creationMode: 'POINT',
      accessType: 'PUBLIC',
      pointNames: [' Trường A '],
      participants: [first, second],
    });
    expect(
      getPointMeetingCreationFingerprint({
        ...input,
        title: 'Meeting',
        pointNames: ['Trường A'],
        participants: [second, { ...first, email: 'first@example.test', name: 'First' }],
      }),
    ).toBe(getPointMeetingCreationFingerprint(input));
    expect(getPointMeetingCreationFingerprint({ ...input, description: 'Changed' })).not.toBe(
      getPointMeetingCreationFingerprint(input),
    );
  });
});
