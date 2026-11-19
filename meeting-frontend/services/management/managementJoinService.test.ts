import { beforeEach, describe, expect, it, vi } from 'vitest';
import { managementFetchJson } from '@/services/management/httpClient';
import { getMeetingJoinInfo, joinManagementMeeting } from './managementJoinService';

vi.mock('@/services/management/httpClient', () => ({
  managementFetchJson: vi.fn(),
}));

const mockedFetchJson = vi.mocked(managementFetchJson);

function backendJoinResponse(attendeeId = 'account:uuid-1') {
  return {
    roomName: 'meeting-demo',
    roomSid: 'RM_1',
    serverUrl: 'ws://livekit.local',
    token: 'jwt-token',
    participant: {
      attendeeId,
      connectionId: 'conn-1',
      liveKitIdentity: `${attendeeId}:conn-1`,
      displayName: attendeeId.startsWith('guest:') ? 'Guest User' : 'Nguyen Van A',
      role: attendeeId.startsWith('guest:') ? 'GUEST' : 'CHAIRMAN',
      chatWriteToken: 'chat-write-token-1',
    },
    permissions: {
      canEndMeeting: !attendeeId.startsWith('guest:'),
      canManageParticipants: !attendeeId.startsWith('guest:'),
    },
  };
}

describe('joinManagementMeeting', () => {
  beforeEach(() => {
    mockedFetchJson.mockReset();
  });

  it('sends the authenticated join body without browser user identity claims', async () => {
    mockedFetchJson.mockResolvedValue(backendJoinResponse());

    await joinManagementMeeting('7', {
      participantType: 'AUTHENTICATED',
      displayName: ' Nguyen Van A ',
    });

    expect(mockedFetchJson).toHaveBeenCalledWith('/api/meetings/7/join', {
      method: 'POST',
      body: JSON.stringify({
        participantType: 'AUTHENTICATED',
        guestId: null,
        displayName: 'Nguyen Van A',
      }),
    });
  });

  it('sends null guestId for a first-time guest join', async () => {
    mockedFetchJson.mockResolvedValue(backendJoinResponse('guest:123'));

    await joinManagementMeeting('7', {
      participantType: 'GUEST',
      displayName: 'Guest User',
    });

    expect(JSON.parse(String(mockedFetchJson.mock.calls[0][1]?.body))).toMatchObject({
      participantType: 'GUEST',
      guestId: null,
      displayName: 'Guest User',
    });
  });

  it('sends a stored guest id for a returning guest', async () => {
    mockedFetchJson.mockResolvedValue(backendJoinResponse('guest:123'));

    await joinManagementMeeting('7', {
      participantType: 'GUEST',
      guestId: 'guest-123',
      displayName: 'Guest User',
    });

    expect(JSON.parse(String(mockedFetchJson.mock.calls[0][1]?.body))).toMatchObject({
      participantType: 'GUEST',
      guestId: 'guest-123',
    });
  });

  it('maps the response participant and permissions correctly', async () => {
    mockedFetchJson.mockResolvedValue(backendJoinResponse('guest:123'));

    const response = await joinManagementMeeting('7', {
      participantType: 'GUEST',
      guestId: 'guest-123',
      displayName: 'Guest User',
    });

    expect(response.participant).toMatchObject({
      attendeeId: 'guest:123',
      displayName: 'Guest User',
      role: 'GUEST',
    });
    expect(response.participant.chatWriteToken).toBe('chat-write-token-1');
    expect(response.permissions.canEndMeeting).toBe(false);
  });

  it('sends the selected point ID for authenticated and guest joins without a client point name', async () => {
    for (const participantType of ['AUTHENTICATED', 'GUEST'] as const) {
      mockedFetchJson.mockResolvedValue({
        ...backendJoinResponse(),
        participant: { ...backendJoinResponse().participant, pointId: 11, pointName: 'Trường A' },
      });
      const response = await joinManagementMeeting('7', {
        participantType,
        displayName: ' A ',
        pointId: 11,
      });
      const body = JSON.parse(String(mockedFetchJson.mock.lastCall?.[1]?.body));
      expect(body.pointId).toBe(11);
      expect(body).not.toHaveProperty('pointName');
      expect(response.participant.pointName).toBe('Trường A');
    }
  });

  it('forwards a POINT guest resume proof without changing ordinary join payloads', async () => {
    mockedFetchJson.mockResolvedValue(backendJoinResponse('guest:123'));
    await joinManagementMeeting('7', {
      participantType: 'GUEST',
      displayName: 'Guest',
      guestId: 'guest-123',
      guestResumeToken: 'secret-proof',
      pointId: 11,
    });
    expect(JSON.parse(String(mockedFetchJson.mock.lastCall?.[1]?.body))).toMatchObject({
      guestId: 'guest-123',
      guestResumeToken: 'secret-proof',
      pointId: 11,
    });
    await joinManagementMeeting('8', { participantType: 'GUEST', displayName: 'Guest' });
    const ordinary = JSON.parse(String(mockedFetchJson.mock.lastCall?.[1]?.body));
    expect(ordinary).not.toHaveProperty('guestResumeToken');
    expect(ordinary).not.toHaveProperty('pointId');
  });
});

describe('getMeetingJoinInfo', () => {
  beforeEach(() => {
    mockedFetchJson.mockReset();
  });

  it('loads only safe public join metadata with GET semantics', async () => {
    mockedFetchJson.mockResolvedValue({
      id: 7,
      title: null,
      startTime: '2026-09-07T09:00:00',
      endTime: '2026-09-07T10:00:00',
      status: 'UPCOMING',
      accessType: 'PRIVATE',
    });

    const response = await getMeetingJoinInfo('7');

    expect(mockedFetchJson).toHaveBeenCalledWith('/api/meetings/7/join-info', {
      method: 'GET',
    });
    expect(response).toEqual({
      id: 7,
      title: null,
      startTime: '2026-09-07T09:00:00',
      endTime: '2026-09-07T10:00:00',
      status: 'UPCOMING',
      accessType: 'PRIVATE',
    });
    expect(response).not.toHaveProperty('roomName');
    expect(response).not.toHaveProperty('roomSid');
    expect(response).not.toHaveProperty('meetingUrl');
    expect(response).not.toHaveProperty('participants');
    expect(response).not.toHaveProperty('email');
    expect(response).not.toHaveProperty('documents');
  });

  it('loads POINT mode and available server-owned point IDs for the chooser', async () => {
    mockedFetchJson.mockResolvedValue({
      id: 7,
      creationMode: 'POINT',
      accessType: 'PUBLIC',
      points: [{ id: 11, name: 'Trường A' }],
    });
    const response = await getMeetingJoinInfo('7');
    expect(response.creationMode).toBe('POINT');
    expect(response.points).toEqual([{ id: 11, name: 'Trường A' }]);
  });
});
