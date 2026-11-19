import { describe, expect, it } from 'vitest';
import type { ManagementMeetingParticipant } from '@/types/management/meeting';
import {
  parseMeetingPointNames,
  validateMeetingPointNames,
  validatePointMeetingParticipantRoles,
} from './meetingPointRules';

describe('point meeting creation boundaries', () => {
  it('parses line endings and surrounding whitespace without creating empty points', () => {
    expect(parseMeetingPointNames('  Trường A\r\n\r\nTrường B \n')).toEqual([
      'Trường A',
      'Trường B',
    ]);
  });
  it('requires at least one nonblank point', () => {
    expect(validateMeetingPointNames(parseMeetingPointNames(' \n '))).not.toBeNull();
    expect(validateMeetingPointNames([' '])).not.toBeNull();
  });
  it('rejects duplicate names regardless of case and outer whitespace', () => {
    expect(validateMeetingPointNames([' Trường A ', 'trường a'])).toContain('trùng');
    expect(validateMeetingPointNames(['Trường A', 'Trường B'])).toBeNull();
    expect(validateMeetingPointNames(['Trường\tA'])).not.toBeNull();
    expect(validateMeetingPointNames(['Trường\u0085A'])).not.toBeNull();
  });
  it('accepts exact count and length limits and rejects values above either limit', () => {
    expect(
      validateMeetingPointNames(Array.from({ length: 100 }, (_, i) => `Điểm ${i}`)),
    ).toBeNull();
    expect(
      validateMeetingPointNames(Array.from({ length: 101 }, (_, i) => `Điểm ${i}`)),
    ).not.toBeNull();
    expect(validateMeetingPointNames(['a'.repeat(150)])).toBeNull();
    expect(validateMeetingPointNames(['a'.repeat(151)])).not.toBeNull();
  });
  it('allows no designated participants but still enforces unique chair and secretary', () => {
    const person = (role: ManagementMeetingParticipant['role']): ManagementMeetingParticipant => ({
      userId: role,
      name: role,
      email: `${role}@example.test`,
      role,
    });
    expect(validatePointMeetingParticipantRoles([])).toBeNull();
    expect(
      validatePointMeetingParticipantRoles([
        person('CHAIRMAN'),
        person('SECRETARY'),
        person('MEMBER'),
      ]),
    ).toBeNull();
    expect(
      validatePointMeetingParticipantRoles([person('CHAIRMAN'), person('CHAIRMAN')]),
    ).toContain('chủ tọa');
    expect(
      validatePointMeetingParticipantRoles([person('SECRETARY'), person('SECRETARY')]),
    ).toContain('thư ký');
  });
});
