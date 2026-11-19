import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  confirmPointCreationRequest,
  getPointCreationRequestId,
  getMeetingCreationRequestId,
  confirmMeetingCreationRequest,
  readPendingMeetingCreation,
} from './pointCreationDraft';

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
  let sequence = 0;
  vi.stubGlobal('crypto', {
    randomUUID: () => `11111111-1111-4111-8111-${String(++sequence).padStart(12, '0')}`,
  });
  return values;
}

describe('durable POINT creation request key', () => {
  it('keeps the key for same-account/same-body retries and changes it for edited metadata', () => {
    storage();
    const first = getPointCreationRequestId('retry-account', 'original-body');
    expect(getPointCreationRequestId('retry-account', 'original-body')).toBe(first);
    const changed = getPointCreationRequestId('retry-account', 'changed-body');
    expect(changed).not.toBe(first);
    expect(getPointCreationRequestId('retry-account', 'changed-body')).toBe(changed);
    expect(getPointCreationRequestId('other-account', 'changed-body')).not.toBe(changed);
  });

  it('recovers an unconfirmed key after a page reload and only clears the matching confirmed receipt', async () => {
    const values = storage();
    const first = getPointCreationRequestId('reload-account', 'body');
    expect(values.size).toBe(1);
    vi.resetModules();
    const reloaded = await import('./pointCreationDraft');
    expect(reloaded.getPointCreationRequestId('reload-account', 'body')).toBe(first);
    reloaded.confirmPointCreationRequest('reload-account', 'wrong-key');
    expect(reloaded.getPointCreationRequestId('reload-account', 'body')).toBe(first);
    reloaded.confirmPointCreationRequest('reload-account', first);
    expect(values.size).toBe(0);
    expect(reloaded.getPointCreationRequestId('reload-account', 'body')).not.toBe(first);
  });

  it('does not let a late old response clear a newer changed-body draft', () => {
    storage();
    const original = getPointCreationRequestId('late-account', 'old');
    const changed = getPointCreationRequestId('late-account', 'new');
    confirmPointCreationRequest('late-account', original);
    expect(getPointCreationRequestId('late-account', 'new')).toBe(changed);
  });

  it('retains an in-memory key with blocked browser storage and tolerates malformed persisted data', () => {
    const values = storage();
    values.set('cusc-point-creation:malformed-account', '{bad json');
    expect(getPointCreationRequestId('malformed-account', 'body')).toMatch(/^[a-f0-9-]{36}$/);
    vi.stubGlobal('window', {
      get sessionStorage() {
        throw new Error('Blocked');
      },
    });
    const first = getPointCreationRequestId('blocked-account', 'body');
    expect(getPointCreationRequestId('blocked-account', 'body')).toBe(first);
    expect(() => confirmPointCreationRequest('blocked-account', first)).not.toThrow();
    expect(() => getPointCreationRequestId('', 'body')).toThrow('đăng nhập');
  });

  it('uses a cryptographic UUID fallback on HTTP origins and fails clearly without randomness', () => {
    storage();
    vi.stubGlobal('crypto', { getRandomValues: (bytes: Uint8Array) => bytes.fill(7) });
    expect(getPointCreationRequestId('http-account', 'body')).toMatch(
      /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/,
    );
    vi.stubGlobal('crypto', {});
    expect(() => getPointCreationRequestId('unsupported-account', 'body')).toThrow('Trình duyệt');
  });
});

describe('creation idempotency for all modes', () => {
  it.each(['PRIVATE', 'POINT', 'LATER', 'INSTANT'])(
    'retains the %s receipt key across reload and clears only its matching scope',
    async (mode) => {
      const values = storage();
      const account = `generic-${mode}`;
      const first = getMeetingCreationRequestId(account, mode, 'same-body');
      const unrelated = getMeetingCreationRequestId(account, 'OTHER', 'same-body');
      expect(first).not.toBe(unrelated);
      vi.resetModules();
      const reloaded = await import('./pointCreationDraft');
      expect(reloaded.getMeetingCreationRequestId(account, mode, 'same-body')).toBe(first);
      reloaded.confirmMeetingCreationRequest(account, mode, first);
      expect(reloaded.readPendingMeetingCreation(account, mode)).toBeNull();
      expect(values.size).toBe(1);
      expect(reloaded.getMeetingCreationRequestId(account, 'OTHER', 'same-body')).toBe(unrelated);
    },
  );
});
