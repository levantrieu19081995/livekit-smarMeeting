import { afterEach, describe, expect, it, vi } from 'vitest';
import { isQnaHint, publishQnaHint, QnaHintGate, QNA_CHANGED_TOPIC } from './qnaRoomSync';

const packet = (value: unknown) => new TextEncoder().encode(JSON.stringify(value));

afterEach(() => vi.useRealTimers());

describe('Q&A room synchronization', () => {
  it('accepts only its dedicated topic and matching meeting', () => {
    expect(isQnaHint(packet({ meetingId: '17' }), QNA_CHANGED_TOPIC, '17')).toBe(true);
    expect(isQnaHint(packet({ meetingId: '17' }), 'lk-chat-topic', '17')).toBe(false);
    expect(isQnaHint(packet({ meetingId: '18' }), QNA_CHANGED_TOPIC, '17')).toBe(false);
    expect(isQnaHint(packet({ meetingId: 17 }), QNA_CHANGED_TOPIC, '17')).toBe(false);
  });

  it('ignores malformed, oversized or content-bearing packets', () => {
    for (const value of [null, [], '17', { meetingId: '17', author: 'guest' }]) {
      expect(isQnaHint(packet(value), QNA_CHANGED_TOPIC, '17')).toBe(false);
    }
    expect(isQnaHint(new Uint8Array([255]), QNA_CHANGED_TOPIC, '17')).toBe(false);
    expect(isQnaHint(new Uint8Array(129), QNA_CHANGED_TOPIC, '17')).toBe(false);
  });

  it('publishes only meeting metadata', async () => {
    const publish = vi.fn().mockResolvedValue(undefined);
    await publishQnaHint('17', publish);
    expect(publish).toHaveBeenCalledOnce();
    const [payload, options] = publish.mock.calls[0];
    expect(JSON.parse(new TextDecoder().decode(payload))).toEqual({ meetingId: '17' });
    expect(options).toEqual({ topic: QNA_CHANGED_TOPIC, reliable: true });
  });

  it('keeps a saved mutation successful when room transport fails', async () => {
    await expect(
      publishQnaHint('17', vi.fn().mockRejectedValue(new Error('offline'))),
    ).resolves.toBeUndefined();
  });

  it('coalesces peer bursts and retains a trailing refresh', () => {
    vi.useFakeTimers();
    const refresh = vi.fn();
    const gate = new QnaHintGate(refresh);
    for (let i = 0; i < 100; i++) gate.request();
    expect(refresh).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(2999);
    expect(refresh).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(1);
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it('cancels pending callbacks permanently when disposed', () => {
    vi.useFakeTimers();
    const refresh = vi.fn();
    const gate = new QnaHintGate(refresh);
    gate.request();
    gate.request();
    gate.dispose();
    vi.advanceTimersByTime(10000);
    gate.request();
    expect(refresh).toHaveBeenCalledTimes(1);
  });
});
