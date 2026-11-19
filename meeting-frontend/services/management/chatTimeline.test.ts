import { describe, expect, it, vi } from 'vitest';
import {
  CHAT_RUNTIME_WINDOW,
  mergeChatTimeline,
  persistChatThenNotify,
  reusableChatDraft,
  confirmsPendingChat,
} from './chatTimeline';
import type { ManagementChatMessageResponse } from '@/types/management/chat';

const message = (id: number): ManagementChatMessageResponse => ({
  id,
  clientMessageId: `message-${id}`,
  senderDisplayName: 'Server name',
  content: `Text ${id}`,
  sentAt: '2026-10-08T09:00:00Z',
  messageType: 'TEXT',
  senderConnectionId: 'connection',
});

describe('authoritative persistent chat', () => {
  it('does not broadcast a message when persistence fails', async () => {
    const notify = vi.fn();
    await expect(
      persistChatThenNotify(async () => {
        throw new Error('DB unavailable');
      }, notify),
    ).rejects.toThrow('DB unavailable');
    expect(notify).not.toHaveBeenCalled();
  });
  it('broadcasts only the confirmed server receipt and remains confirmed when broadcasting fails', async () => {
    const receipt = message(1);
    const notify = vi.fn().mockRejectedValue(new Error('LiveKit unavailable'));
    await expect(persistChatThenNotify(async () => receipt, notify)).resolves.toBe(receipt);
    await Promise.resolve();
    expect(notify).toHaveBeenCalledWith(receipt);
  });
  it('does not treat a synchronous notification failure as a failed DB write', async () => {
    await expect(
      persistChatThenNotify(
        async () => message(2),
        () => {
          throw new Error('Disconnected');
        },
      ),
    ).resolves.toMatchObject({ id: 2 });
  });
  it('merges lost-response retries and restored history without duplicate or out-of-order rows', () => {
    expect(mergeChatTimeline([message(3), message(1)], [message(2), message(3)])).toEqual([
      message(1),
      message(2),
      message(3),
    ]);
  });
  it('bounds the browser window while retaining the latest canonical messages', () => {
    const rows = Array.from({ length: 900 }, (_, index) => message(index + 1));
    const retained = mergeChatTimeline([], rows);
    expect(retained).toHaveLength(CHAT_RUNTIME_WINDOW);
    expect(retained[0].id).toBe(401);
    expect(retained[retained.length - 1]?.id).toBe(900);
  });
  it('retains the same mutation ID for an unknown write until its content changes', () => {
    const createId = vi.fn().mockReturnValueOnce('first').mockReturnValueOnce('second');
    const draft = reusableChatDraft(null, ' Hello ', createId);
    expect(reusableChatDraft(draft, 'Hello', createId)).toBe(draft);
    expect(reusableChatDraft(draft, 'Changed', createId)).toEqual({
      clientMessageId: 'second',
      content: 'Changed',
    });
    expect(createId).toHaveBeenCalledTimes(2);
  });
  it('reconciles an unknown write only from the same canonical author/content/client ID', () => {
    const record = message(1);
    const pending = { clientMessageId: record.clientMessageId, content: record.content };
    expect(confirmsPendingChat([record], pending, 'connection')).toBe(true);
    expect(confirmsPendingChat([record], pending, 'other-connection')).toBe(false);
    expect(confirmsPendingChat([{ ...record, content: 'Other' }], pending, 'connection')).toBe(
      false,
    );
    expect(
      confirmsPendingChat([{ ...record, clientMessageId: 'Other' }], pending, 'connection'),
    ).toBe(false);
  });
});
