import { describe, expect, it } from 'vitest';
import { getPointMeetingCreationFingerprint } from './managementMeetingApi';
import { recoverPointCreationInput, recoverMeetingCreationInput } from './pointCreationRecovery';
import type { ManagementMeetingMutationInput } from '@/types/management/meeting';

const input: ManagementMeetingMutationInput = {
  title: 'Cuộc họp điểm cầu',
  description: 'Nội dung họp',
  creationMode: 'POINT',
  accessType: 'PUBLIC',
  startTime: '2026-10-08T09:00:00',
  endTime: '2026-10-08T11:00:00',
  pointNames: ['Trường A', 'Trường B'],
  participants: [
    { userId: 'account-chair', name: 'Chủ tọa', email: 'chair@example.test', role: 'CHAIRMAN' },
  ],
  documents: [{ id: 'local-document', name: 'Not persisted file' }],
};

describe('unconfirmed POINT creation recovery', () => {
  it('restores metadata, points, roster and exact canonical retry body without fabricating documents', () => {
    const fingerprint = getPointMeetingCreationFingerprint(input);
    const recovered = recoverPointCreationInput(fingerprint);
    expect(recovered).toMatchObject({
      title: input.title,
      startTime: input.startTime,
      endTime: input.endTime,
      pointNames: input.pointNames,
      participants: [{ userId: 'account-chair', name: 'Chủ tọa', role: 'CHAIRMAN' }],
      documents: [],
    });
    expect(recovered).not.toHaveProperty('canManage');
    expect(getPointMeetingCreationFingerprint(recovered!)).toBe(fingerprint);
  });
  it('rejects forged ownership, participant roles, extra fields and private mode in cached data', () => {
    const payload = JSON.parse(getPointMeetingCreationFingerprint(input));
    for (const changed of [
      { ...payload, createdById: 'other-account' },
      { ...payload, canManage: true },
      { ...payload, accessType: 'PRIVATE' },
      { ...payload, participants: [{ ...payload.participants[0], role: 'ADMIN' }] },
      {
        ...payload,
        participants: [{ ...payload.participants[0], participantKey: 'other-participant' }],
      },
    ])
      expect(recoverPointCreationInput(JSON.stringify(changed))).toBeNull();
  });
  it('rejects malformed data, invalid schedules and excessive or duplicate point/roster data', () => {
    const payload = JSON.parse(getPointMeetingCreationFingerprint(input));
    expect(recoverPointCreationInput('{bad-json')).toBeNull();
    expect(recoverPointCreationInput('x'.repeat(1000001))).toBeNull();
    for (const changed of [
      { ...payload, startTime: 'bad-time' },
      { ...payload, endTime: payload.startTime },
      { ...payload, pointNames: ['Trường A', 'trường a'] },
      { ...payload, pointNames: ['a'.repeat(151)] },
      { ...payload, participants: [payload.participants[0], payload.participants[0]] },
      { ...payload, participants: [{ ...payload.participants[0], userPublicId: '' }] },
    ])
      expect(recoverPointCreationInput(JSON.stringify(changed))).toBeNull();
  });
});

describe('mode-scoped creation recovery', () => {
  it('preserves a valid large description exactly for an unknown-write retry', () => {
    const fingerprint = getPointMeetingCreationFingerprint({
      ...input,
      description: 'x'.repeat(100_000),
    });
    const recovered = recoverMeetingCreationInput(fingerprint, 'POINT');
    expect(recovered?.description).toHaveLength(100_000);
    expect(getPointMeetingCreationFingerprint(recovered!)).toBe(fingerprint);
  });
  it('restores a PRIVATE schedule without adding point data or ownership', () => {
    const privateInput = {
      ...input,
      creationMode: 'SCHEDULED' as const,
      accessType: 'PRIVATE' as const,
      pointNames: undefined,
    };
    const fingerprint = getPointMeetingCreationFingerprint(privateInput);
    const recovered = recoverMeetingCreationInput(fingerprint, 'SCHEDULED');
    expect(recovered).toMatchObject({
      accessType: 'PRIVATE',
      creationMode: 'SCHEDULED',
      title: privateInput.title,
      documents: [],
    });
    expect(recovered).not.toHaveProperty('pointNames');
    expect(getPointMeetingCreationFingerprint(recovered!)).toBe(fingerprint);
    expect(recoverMeetingCreationInput(fingerprint, 'POINT')).toBeNull();
  });
  it.each(['LATER', 'INSTANT'] as const)(
    'recovers %s without inventing a scheduled end time',
    (creationMode) => {
      const fingerprint = getPointMeetingCreationFingerprint({
        title: '',
        description: '',
        creationMode,
        accessType: 'PUBLIC',
        startTime: null,
        endTime: null,
        participants: [],
        documents: [],
      });
      const recovered = recoverMeetingCreationInput(fingerprint, creationMode);
      expect(recovered).toMatchObject({
        creationMode,
        startTime: null,
        endTime: null,
        participants: [],
      });
      expect(getPointMeetingCreationFingerprint(recovered!)).toBe(fingerprint);
      expect(recoverMeetingCreationInput(fingerprint, 'SCHEDULED')).toBeNull();
    },
  );
});
