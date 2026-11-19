import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  clearPointGuestResume,
  readPointGuestResume,
  storePointGuestResume,
} from './pointGuestResume';

afterEach(() => vi.unstubAllGlobals());

function storage() {
  const values = new Map<string, string>();
  vi.stubGlobal('window', {
    sessionStorage: {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
    },
  });
  return values;
}

describe('POINT guest resume proof storage', () => {
  it('stores guest identity with its proof per meeting and replaces the proof after rejoin', () => {
    storage();
    storePointGuestResume('7', 'guest:abc', 'old-proof');
    expect(readPointGuestResume('7')).toEqual({
      guestId: 'guest-abc',
      guestResumeToken: 'old-proof',
    });
    expect(readPointGuestResume('8')).toBeUndefined();
    storePointGuestResume('7', 'guest:abc', 'new-proof');
    expect(readPointGuestResume('7')?.guestResumeToken).toBe('new-proof');
    clearPointGuestResume('7');
    expect(readPointGuestResume('7')).toBeUndefined();
  });
  it('ignores malformed storage and identity-only entries rather than asserting ownership', () => {
    const values = storage();
    for (const value of [
      'bad json',
      'null',
      '{}',
      '{"guestId":"guest-abc"}',
      '{"guestId":"account-abc","guestResumeToken":"proof"}',
    ]) {
      values.set('cusc-point-guest-resume:7', value);
      expect(readPointGuestResume('7')).toBeUndefined();
    }
    storePointGuestResume('8', 'account:abc', 'proof');
    storePointGuestResume('9', 'guest:abc', '');
    expect(readPointGuestResume('8')).toBeUndefined();
    expect(readPointGuestResume('9')).toBeUndefined();
  });
  it('does not fail a successful join when browser storage is unavailable', () => {
    vi.stubGlobal('window', {
      get sessionStorage() {
        throw new Error('Denied');
      },
    });
    expect(readPointGuestResume('7')).toBeUndefined();
    expect(() => storePointGuestResume('7', 'guest:abc', 'proof')).not.toThrow();
    expect(() => clearPointGuestResume('7')).not.toThrow();
  });
});
