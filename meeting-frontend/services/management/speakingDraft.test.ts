import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  newSpeakingRequestId,
  readSpeakingDraft,
  speakingDraftKey,
  storeSpeakingDraft,
} from './speakingDraft';

const id = '3432eb6f-9b3a-40ee-bfc8-e56fd75aab02';

describe('Unconfirmed speaking submission recovery', () => {
  afterEach(() => vi.unstubAllGlobals());
  function storage() {
    const data = new Map<string, string>();
    vi.stubGlobal('window', {
      sessionStorage: {
        getItem: (key: string) => data.get(key) ?? null,
        setItem: (key: string, value: string) => data.set(key, value),
        removeItem: (key: string) => data.delete(key),
      },
    });
    return data;
  }
  it('recovers the same idempotency ID on reopening and isolates meeting/account boundaries', () => {
    storage();
    const key = speakingDraftKey('37', 'account:1');
    const draft = { requestId: id, pointId: 2, speakerName: 'Bình' };
    storeSpeakingDraft(key, draft);
    expect(readSpeakingDraft(key)).toEqual(draft);
    expect(readSpeakingDraft(speakingDraftKey('38', 'account:1'))).toBeNull();
    expect(readSpeakingDraft(speakingDraftKey('37', 'account:2'))).toBeNull();
    storeSpeakingDraft(key, null);
    expect(readSpeakingDraft(key)).toBeNull();
  });
  it.each([
    'broken',
    JSON.stringify({ requestId: 'bad', pointId: 2, speakerName: '' }),
    JSON.stringify({ requestId: id, pointId: -1, speakerName: '' }),
    JSON.stringify({ requestId: id, pointId: 2, speakerName: 'a'.repeat(256) }),
  ])('ignores damaged or invalid stored operations', (value) => {
    const data = storage();
    data.set('key', value);
    expect(readSpeakingDraft('key')).toBeNull();
  });
  it('keeps UI usable when browser storage is unavailable', () => {
    vi.stubGlobal('window', {
      get sessionStorage() {
        throw new Error('restricted');
      },
    });
    expect(readSpeakingDraft('key')).toBeNull();
    expect(() =>
      storeSpeakingDraft('key', { requestId: id, pointId: 1, speakerName: '' }),
    ).not.toThrow();
  });
  it('creates a UUID when randomUUID is unavailable on an HTTP development origin', () => {
    vi.stubGlobal('crypto', { getRandomValues: (bytes: Uint8Array) => bytes.fill(7) });
    expect(newSpeakingRequestId()).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
  });
  it('reports unavailable browser cryptography as a recoverable form error', () => {
    vi.stubGlobal('crypto', undefined);
    expect(() => newSpeakingRequestId()).toThrow('Trình duyệt');
  });
});
