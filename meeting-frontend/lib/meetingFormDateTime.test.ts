import { describe, expect, it } from 'vitest';
import { getMeetingFormDateTime } from './meetingFormDateTime';

describe('meeting schedule form timezone semantics', () => {
  it('preserves timezone-less backend LocalDateTime without shifting the editable time', () => {
    expect(getMeetingFormDateTime('2026-10-08T09:15:00')).toEqual({
      date: '2026-10-08',
      time: '09:15',
    });
    expect(getMeetingFormDateTime('2026-10-08T00:05')).toEqual({
      date: '2026-10-08',
      time: '00:05',
    });
    expect(getMeetingFormDateTime('2026-10-08T09:15:59.123456789')).toEqual({
      date: '2026-10-08',
      time: '09:15',
    });
  });
  it('converts historical zoned values to the Vietnam business timezone independent of the device zone', () => {
    expect(getMeetingFormDateTime('2026-10-08T02:15:00Z')).toEqual({
      date: '2026-10-08',
      time: '09:15',
    });
    expect(getMeetingFormDateTime('2026-10-08T09:15:00+07:00')).toEqual({
      date: '2026-10-08',
      time: '09:15',
    });
    expect(getMeetingFormDateTime('2026-10-08T09:15:00-04:00')).toEqual({
      date: '2026-10-08',
      time: '20:15',
    });
  });
  it('handles date rollover and midnight when converting zoned timestamps', () => {
    expect(getMeetingFormDateTime('2026-10-08T20:30:00Z')).toEqual({
      date: '2026-10-09',
      time: '03:30',
    });
    expect(getMeetingFormDateTime('2026-10-08T17:00:00Z')).toEqual({
      date: '2026-10-09',
      time: '00:00',
    });
  });
  it('returns empty controls for missing or invalid calendar/time values', () => {
    for (const value of [
      null,
      '',
      'invalid',
      '2026-02-30T09:00:00',
      '2026-10-08T25:00:00',
      '2026-10-08T09:00:61',
      '2026-10-08T09:00:00+99:00',
    ]) {
      expect(getMeetingFormDateTime(value)).toEqual({ date: '', time: '' });
    }
  });
});
