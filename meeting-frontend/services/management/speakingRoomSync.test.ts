import { describe, expect, it, vi } from 'vitest';
import { isSpeakingHint, publishSpeakingHint, SPEAKING_CHANGED_TOPIC } from './speakingRoomSync';

const encode = (value: unknown) => new TextEncoder().encode(JSON.stringify(value));

describe('Untrusted room hints never supply authoritative speaking state', () => {
  it('accepts only a matching meeting reload hint', () => {
    expect(isSpeakingHint(encode({ meetingId: '37' }), SPEAKING_CHANGED_TOPIC, '37')).toBe(true);
    expect(isSpeakingHint(encode({ meetingId: '38' }), SPEAKING_CHANGED_TOPIC, '37')).toBe(false);
    expect(isSpeakingHint(encode({ meetingId: '37' }), 'other', '37')).toBe(false);
  });
  it('rejects forged permissions, invalid UTF8 and oversized payloads', () => {
    expect(
      isSpeakingHint(encode({ meetingId: '37', canModerate: true }), SPEAKING_CHANGED_TOPIC, '37'),
    ).toBe(false);
    expect(isSpeakingHint(new Uint8Array([0xff]), SPEAKING_CHANGED_TOPIC, '37')).toBe(false);
    expect(isSpeakingHint(new Uint8Array(129), SPEAKING_CHANGED_TOPIC, '37')).toBe(false);
  });
  it('sends no identity or timeline content over the hint channel', async () => {
    const publish = vi.fn().mockResolvedValue(undefined);
    await publishSpeakingHint('37', publish);
    expect(JSON.parse(new TextDecoder().decode(publish.mock.calls[0][0]))).toEqual({
      meetingId: '37',
    });
    expect(publish.mock.calls[0][1]).toEqual({ topic: SPEAKING_CHANGED_TOPIC, reliable: true });
  });
  it('does not reverse a saved HTTP operation when room transport fails', async () => {
    await expect(
      publishSpeakingHint('37', async () => {
        throw new Error('offline');
      }),
    ).resolves.toBeUndefined();
  });
});
