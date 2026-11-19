import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ManagementMeetingJoinInfo } from '@/types/management/join';
import {
  findSelectedMeetingPoint,
  readMeetingPointSelection,
  storeMeetingPointSelection,
} from './meetingPointSelection';

const pointMeeting: ManagementMeetingJoinInfo = {
  id: 7,
  title: 'Điểm cầu',
  startTime: null,
  endTime: null,
  status: 'IN_PROGRESS',
  accessType: 'PUBLIC',
  creationMode: 'POINT',
  points: [
    { id: 11, name: 'Trường A' },
    { id: 12, name: 'Trường B' },
  ],
};

afterEach(() => vi.unstubAllGlobals());

describe('meeting-scoped point choice', () => {
  it('only accepts IDs provided for the current POINT meeting', () => {
    expect(findSelectedMeetingPoint(pointMeeting, 11)?.name).toBe('Trường A');
    for (const id of [null, 0, -1, 11.5, NaN, 99])
      expect(findSelectedMeetingPoint(pointMeeting, id)).toBeNull();
    expect(findSelectedMeetingPoint({ ...pointMeeting, creationMode: 'SCHEDULED' }, 11)).toBeNull();
    expect(findSelectedMeetingPoint({ ...pointMeeting, points: [] }, 11)).toBeNull();
  });
  it('keeps refresh selection scoped per meeting and allows clearing stale selection', () => {
    const values = new Map<string, string>();
    vi.stubGlobal('window', {
      sessionStorage: {
        getItem: (key: string) => values.get(key) ?? null,
        setItem: (key: string, value: string) => values.set(key, value),
        removeItem: (key: string) => values.delete(key),
      },
    });
    storeMeetingPointSelection('7', 11);
    expect(readMeetingPointSelection('7')).toBe(11);
    expect(readMeetingPointSelection('8')).toBeNull();
    storeMeetingPointSelection('7', null);
    expect(readMeetingPointSelection('7')).toBeNull();
    values.set('cusc-meeting-point:7', '11x');
    expect(readMeetingPointSelection('7')).toBeNull();
    values.set('cusc-meeting-point:7', '9007199254740992');
    expect(readMeetingPointSelection('7')).toBeNull();
  });
  it('does not prevent joining if browser storage is disabled', () => {
    vi.stubGlobal('window', {
      get sessionStorage() {
        throw new Error('Storage blocked');
      },
    });
    expect(readMeetingPointSelection('7')).toBeNull();
    expect(() => storeMeetingPointSelection('7', 11)).not.toThrow();
  });
});
