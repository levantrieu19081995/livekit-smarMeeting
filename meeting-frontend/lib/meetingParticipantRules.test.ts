import { describe, expect, it } from 'vitest';
import type {
  ManagementMeetingParticipant,
  ManagementMeetingRole,
} from '@/types/management/meeting';
import {
  getDefaultMeetingParticipantRole,
  getMeetingParticipantRoleOptions,
  validateMeetingParticipantRoles,
  validateMeetingParticipants,
} from './meetingParticipantRules';

function person(
  userId: string,
  role: ManagementMeetingRole,
  overrides: Partial<ManagementMeetingParticipant> = {},
): ManagementMeetingParticipant {
  return {
    userId,
    participantKey: `pk-${userId}`,
    name: userId,
    email: `${userId}@example.invalid`,
    role,
    status: 'INVITED',
    ...overrides,
  };
}

const chairman = person('chairman', 'CHAIRMAN');
const secretary = person('secretary', 'SECRETARY');
const guest = person('guest-1', 'GUEST', { status: 'JOINED', joinedAt: '2026-10-05T09:00:00' });

describe('Meeting participant selection and edit validation', () => {
  it('offers chairman, secretary and member in both create and edit forms', () => {
    expect(getMeetingParticipantRoleOptions('PUBLIC', 'edit')).toEqual([
      'CHAIRMAN',
      'SECRETARY',
      'MEMBER',
    ]);
    expect(getMeetingParticipantRoleOptions('PUBLIC', 'create')).toEqual([
      'CHAIRMAN',
      'SECRETARY',
      'MEMBER',
    ]);
    expect(getMeetingParticipantRoleOptions('PRIVATE', 'create')).toEqual([
      'CHAIRMAN',
      'SECRETARY',
      'MEMBER',
    ]);
    expect(getMeetingParticipantRoleOptions('PRIVATE', 'edit')).toEqual([
      'CHAIRMAN',
      'SECRETARY',
      'MEMBER',
    ]);
  });

  it('defaults all newly selected people to member when editing a public meeting with a chairman', () => {
    const assignedRoles: ManagementMeetingRole[] = ['CHAIRMAN', 'SECRETARY', 'GUEST'];
    const selected = ['new-1', 'new-2', 'new-3'].map((userId) => {
      const role = getDefaultMeetingParticipantRole('PUBLIC', assignedRoles, 'edit');
      assignedRoles.push(role);
      return person(userId, role, { participantKey: undefined });
    });
    expect(selected.map((participant) => participant.role)).toEqual(['MEMBER', 'MEMBER', 'MEMBER']);
    expect(
      validateMeetingParticipants('PUBLIC', [chairman, secretary, guest, ...selected], 'edit', [
        chairman,
        secretary,
        guest,
      ]),
    ).toBeNull();
  });

  it('selects a chairman first if missing, then members when editing', () => {
    expect(getDefaultMeetingParticipantRole('PUBLIC', ['SECRETARY'], 'edit')).toBe('CHAIRMAN');
    expect(getDefaultMeetingParticipantRole('PUBLIC', ['SECRETARY', 'CHAIRMAN'], 'edit')).toBe(
      'MEMBER',
    );
  });

  it('assigns only one chairman and secretary by default when creating either room type', () => {
    expect(getDefaultMeetingParticipantRole('PUBLIC', [], 'create')).toBe('CHAIRMAN');
    expect(getDefaultMeetingParticipantRole('PUBLIC', ['CHAIRMAN'], 'create')).toBe('SECRETARY');
    expect(getDefaultMeetingParticipantRole('PRIVATE', [], 'create')).toBe('CHAIRMAN');
    expect(getDefaultMeetingParticipantRole('PRIVATE', ['CHAIRMAN'], 'create')).toBe('SECRETARY');
    for (const accessType of ['PUBLIC', 'PRIVATE'] as const) {
      const assigned: ManagementMeetingRole[] = [];
      for (let index = 0; index < 5; index++) {
        assigned.push(getDefaultMeetingParticipantRole(accessType, assigned, 'create'));
      }
      expect(assigned).toEqual(['CHAIRMAN', 'SECRETARY', 'MEMBER', 'MEMBER', 'MEMBER']);
    }
  });

  it('accepts newly added members when saving a public meeting edit', () => {
    expect(
      validateMeetingParticipants('PUBLIC', [chairman, person('new', 'MEMBER')], 'edit', [
        chairman,
      ]),
    ).toBeNull();
    expect(
      validateMeetingParticipantRoles(
        'PUBLIC',
        [chairman, { ...secretary, role: 'MEMBER' }],
        'edit',
        [chairman, secretary],
      ),
    ).toBeNull();
  });

  it('accepts named members at creation but does not permit inviting new public guests', () => {
    expect(
      validateMeetingParticipants(
        'PUBLIC',
        [chairman, secretary, person('new', 'MEMBER')],
        'create',
      ),
    ).toBeNull();
    expect(
      validateMeetingParticipants('PUBLIC', [chairman, person('new', 'GUEST')], 'create'),
    ).toContain('chủ tọa, thư ký hoặc thành viên');
  });

  it.each(['PUBLIC', 'PRIVATE'] as const)(
    'rejects two secretaries before creating a %s meeting',
    (accessType) => {
      expect(
        validateMeetingParticipants(
          accessType,
          [chairman, secretary, person('secretary-2', 'SECRETARY')],
          'create',
        ),
      ).toContain('một thư ký');
      expect(
        validateMeetingParticipants(accessType, [chairman, person('new', 'MEMBER')], 'create'),
      ).toBeNull();
    },
  );

  it.each(['JOINED', 'LEFT'] as const)(
    'keeps a persisted public guest with status %s while adding members',
    (status) => {
      const attendee = { ...guest, status };
      expect(
        validateMeetingParticipants(
          'PUBLIC',
          [chairman, attendee, person('new', 'MEMBER')],
          'edit',
          [chairman, attendee],
        ),
      ).toBeNull();
    },
  );

  it('does not offer or permit assigning a new guest in the participant picker', () => {
    expect(getMeetingParticipantRoleOptions('PUBLIC', 'edit')).not.toContain('GUEST');
    expect(
      validateMeetingParticipants('PUBLIC', [chairman, person('new', 'GUEST')], 'edit', [chairman]),
    ).not.toBeNull();
  });

  it('requires the persisted participant identity to retain a guest role', () => {
    expect(
      validateMeetingParticipants(
        'PUBLIC',
        [chairman, { ...guest, participantKey: 'other-key' }],
        'edit',
        [chairman, guest],
      ),
    ).not.toBeNull();
    expect(
      validateMeetingParticipants('PUBLIC', [chairman, { ...secretary, role: 'GUEST' }], 'edit', [
        chairman,
        secretary,
      ]),
    ).not.toBeNull();
  });

  it('keeps the existing guest role selectable only for that attendee in the edit form', () => {
    expect(getMeetingParticipantRoleOptions('PUBLIC', 'edit', guest, [guest])).toEqual([
      'CHAIRMAN',
      'SECRETARY',
      'MEMBER',
      'GUEST',
    ]);
    expect(
      getMeetingParticipantRoleOptions('PUBLIC', 'edit', secretary, [guest, secretary]),
    ).not.toContain('GUEST');
    expect(getMeetingParticipantRoleOptions('PUBLIC', 'create', guest, [guest])).toEqual([
      'CHAIRMAN',
      'SECRETARY',
      'MEMBER',
    ]);
  });

  it.each(['create', 'edit'] as const)(
    'preserves the chairman and secretary rules in %s',
    (mode) => {
      expect(validateMeetingParticipants('PUBLIC', [secretary], mode)).toContain(
        'Vui lòng chọn một chủ tọa',
      );
      expect(
        validateMeetingParticipantRoles('PUBLIC', [chairman, person('second', 'CHAIRMAN')], mode),
      ).toContain('một chủ tọa');
      expect(validateMeetingParticipantRoles('PUBLIC', [secretary], mode)).toBeNull();
      const multipleSecretaries = validateMeetingParticipants(
        'PUBLIC',
        [chairman, secretary, person('secretary-2', 'SECRETARY')],
        mode,
      );
      if (mode === 'create') {
        expect(multipleSecretaries).toContain('một thư ký');
      } else {
        expect(multipleSecretaries).toBeNull();
      }
    },
  );

  it('preserves private meeting validation', () => {
    expect(validateMeetingParticipants('PRIVATE', [], 'edit')).toContain('ít nhất một người');
    expect(
      validateMeetingParticipants('PRIVATE', [chairman, person('new', 'MEMBER')], 'edit'),
    ).toBeNull();
  });
});
