import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  clearStoredGuestId,
  getStoredGuestId,
  storeGuestId,
} from './managementGuestIdentityService';

function createSessionStorage() {
  const values = new Map<string, string>();

  return {
    getItem: vi.fn((key: string) => values.get(key) ?? null),
    setItem: vi.fn((key: string, value: string) => {
      values.set(key, value);
    }),
    removeItem: vi.fn((key: string) => {
      values.delete(key);
    }),
  };
}

describe('managementGuestIdentityService', () => {
  beforeEach(() => {
    vi.stubGlobal('window', {
      sessionStorage: createSessionStorage(),
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('stores and reads guest id per meeting from sessionStorage', () => {
    storeGuestId('7', 'guest-123');
    storeGuestId('8', 'guest-456');

    expect(getStoredGuestId('7')).toBe('guest-123');
    expect(getStoredGuestId('8')).toBe('guest-456');
  });

  it('ignores stored ids without the guest prefix', () => {
    window.sessionStorage.setItem('management-meeting-guest:7', 'U001');

    expect(getStoredGuestId('7')).toBeUndefined();
  });

  it('clears a stored guest id', () => {
    storeGuestId('7', 'guest-123');
    clearStoredGuestId('7');

    expect(getStoredGuestId('7')).toBeUndefined();
  });
});
